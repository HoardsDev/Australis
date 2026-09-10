# Australis — Live Flood-Test Findings (2026-09-10)

First live test of the Velocity plugin against a real proxy, run from a local
session (the cloud sandbox couldn't reach out). This is the honest,
evidence-backed counterpart to `ROADMAP.md` — read both. **Headline: the L7
plugin, as currently architected, was never exercised by the flood and would add
little against it in production. The reasons are structural, not a config typo.**

## Setup

- **Target:** the test network prod Velocity proxy (`203.0.113.10:25565`), Velocity
  3.5.1, `online-mode=true`, `player-info-forwarding-mode=modern`,
  `login-ratelimit=3000`. Box: OVH dedi, Ryzen 7 9700X (8c/16t), 62 GB, pre-launch.
- **Plugin:** `australis-velocity-0.2.0.jar`, built locally (JDK 21), dropped into
  the proxy's `plugins/`, proxy restarted. Loaded clean: `Australis 0.2.0
  enabled — L7 protection active`, Velocity `Done (0.36s)`.
- **Attacker:** `testkit/floodtest`, single home box (egress `198.51.100.10`),
  then a 4-source distributed run (added fleet boxes `edge-a 203.0.113.11`,
  `edge-b 203.0.113.12`, `edge-c 203.0.113.13`).
- **Instrumentation:** floodtest client-side counters; `log-blocks: true` on the
  plugin; SSH health-watch on the proxy container (CPU + host load).

## Results

| Run | Source(s) | Rate | engaged | rejected | proxy CPU peak | Australis action lines |
|---|---|---|---|---|---|---|
| Baseline (no plugin), join | 1 IP | 1,609/s | 11 | 48,258 | ~10% | n/a |
| Protected, join | 1 IP | 1,667/s | 10 | 50,006 | ~40%→5% | **0** |
| Protected, ping | 1 IP | ~880/s | 13,229 | 0 | — | **0** |
| Protected, join | **4 IPs** | ~5,400/s agg | ~10 **per IP** | ~163k agg | **14%** | **0** |

- Baseline ≡ protected. The plugin changed nothing measurable.
- Every source IP got **~1 login per 3 s** through — exactly Velocity's
  `login-ratelimit=3000`.
- Host load never exceeded **0.48 / 16 threads**. A handful of boxes cannot
  volumetrically threaten this hardware; this was an L7 correctness probe, not a
  capacity test.

## Root cause

**All plugin defenses hook `PreLoginEvent`, which is downstream of Velocity's own
per-IP login throttle.** Velocity accepts one login per IP per 3 s and rejects
the rest with a login-disconnect (which floodtest scores as "rejected") *before*
`PreLoginEvent` fires. So:

1. The per-IP `ConnectionRateLimiter` never sees enough traffic to trip — Velocity
   already dropped it.
2. The global `AttackDetector` counts inside `onPreLogin`, so it only ever sees
   the post-throttle trickle (~1.3 logins/s across 4 IPs). Its 60-conn/s "under
   attack" threshold would need **~180+ distinct IPs** to arm — so verification,
   limbo routing, and edge-blocklist (all attack-gated) never turn on.
3. The ping limiter is **inert on Velocity**: `ProxyPingEvent` cannot refuse a
   response (`setPing` is `@NotNull`; the code notes this). All 13,229 pings were
   served. Only `PingCache` provides any benefit on the ping path.

Net: for connection/login floods, the L7 plugin is **redundant with a stock
Velocity setting**, and it's structurally blind to the true attack volume.

## Recommendations (priority order)

1. **Detection moved to the handshake stage** — implemented and validated (see
   "Validation" below). `onHandshake(ConnectionHandshakeEvent)` now drives
   `AttackDetector` + the per-IP connection limiter; `onPreLogin` only enforces
   the kick (peek, no double-count). This is the correct place for the plugin's
   logic and it works once traffic reaches it.
2. **But even `ConnectionHandshakeEvent` is downstream of Velocity's
   `login-ratelimit`.** Proven live: with `login-ratelimit=3000`, the flood never
   reaches *any* plugin event; set it to `0` and Australis immediately fires
   (`ATTACK DETECTED`, `flood-limited … pushed to edge`). So **Velocity's built-in
   throttle already handles single-/few-IP connection floods for free**, and no
   plugin hook can act earlier than it. The plugin layer only adds value where
   the built-in can't: (a) **distributed** floods (many IPs each under the per-IP
   throttle but collectively an attack) → attack-mode arms verification/limbo;
   (b) pushing confirmed abusers to the **edge** for a kernel drop. True
   see-everything/drop-everything at the proxy needs a **Netty pipeline handler**
   injected into Velocity (Sonar-style), which is the only thing upstream of
   `login-ratelimit`.
3. **The real anti-DDoS is the Go `edge/` (XDP / L3-L4 + IP hiding).** That is the
   layer that deserves a high-PPS load test, and it is testable independently of
   the proxy. Prioritise it over further L7 work.
4. **Fix the harness before the next L7 run:** many distinct source IPs (100s) or
   source churn; read and record the disconnect reason; add a mode that completes
   login against an offline-mode limbo so the verification path can be exercised.
5. **Document the ping limiter as cache-only** on Velocity, or drop the limiter
   knob to avoid implying protection it can't deliver.

## Validation of the handshake-stage fix (same session)

After moving detection/limiting to `onHandshake`, a repeat single-IP flood with
`login-ratelimit=3000` still showed **0** Australis action lines — confirming the
throttle is upstream of the handshake event. Temporarily setting
`login-ratelimit=0` and re-flooding produced, as designed:

```
Australis flood-limited 198.51.100.10 (connection-flood) — pushed to edge; kicked at login while banned
Australis: ATTACK DETECTED — aggressive defences armed (verification on).
```

`engaged` rose 7 → 126 (traffic now reaching login), the per-IP limiter tripped
and short-circuited `onPreLogin` (so 0 verification challenges for a single IP —
correct; challenges are the distributed-flood path). `login-ratelimit` was
restored to `3000` immediately after. **Conclusion: the fix is correct, but its
value is realised only against distributed attacks or with the edge in front —
for single-IP abuse, stock Velocity already wins.**

## What this does NOT tell us

- Nothing about volumetric / L3-L4 capacity (untested — needs the edge).
- Nothing about the verification/limbo flow (never armed).
- Nothing about many-IP botnet behaviour beyond 4 sources.
