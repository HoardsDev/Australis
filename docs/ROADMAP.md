# Australis — Build Roadmap

Ordered by value-per-effort. Each phase is independently useful — ship as you go.
`[x]` = done in this repo, `[~]` = partial / API-limited (see note), `[ ]` = todo.

> Also see `CHANGELOG.md` for recent changes and `FINDINGS-live-test.md` for the
> live flood-test results that shaped the current priorities. As of 2026-09-10 the
> plugin + edge + nftables stack is hardened, security-audited, and live-validated,
> and the **XDP/eBPF line-rate filter is now shipped** (opt-in) and validated on a
> test box. Remaining: Bedrock/RakNet edge, deeper in-XDP protocol validation, IPv6.

## Phase 1 — L7 plugin core ✅
**Deliverable:** a Velocity plugin that already stops most attacks.
- [x] Project scaffold (Gradle wrapper, plugin main, config)
- [x] Per-IP connection rate limiter (sliding window + temp-ban + self-pruning)
- [x] Per-IP login rate limiter + connect/disconnect churn detection
- [x] Global attack detector (gates aggressive defences to real attacks)
- [x] Status/ping response cache + per-IP ping rate limit
- [x] Config schema + live hot reload (`/australis reload`)
- [x] Metrics counters + `/australis stats` command
- [x] Detection + per-IP connection limiting at the earliest event
      (`ConnectionHandshakeEvent`), enforced at `PreLogin`. NOTE: Velocity's own
      `login-ratelimit` sits upstream of all plugin events, so single-/few-IP
      connection floods are handled by Velocity before the plugin — see
      `FINDINGS-live-test.md`.
- [~] True Netty-pipeline rejection (Sonar-style, ahead of `login-ratelimit`) —
      v2; would let the plugin drop floods it currently only detects.

## Phase 2 — Bot verification ✅ (API-level) / [ ] (deep)
**Deliverable:** near-total bot-join immunity.
- [x] Reconnect challenge (attack-gated) — dumb flood bots never reconnect
- [x] Verified-IP allowlist with TTL; auto-verify on successful session
- [x] Operator allowlist + manual `/australis verify|unverify`
- [x] Pass → forward; fail → (via limiters) drop + feed to edge blocklist
- [x] **Deep:** full limbo flow implemented — proxy routing (`LimboRouter` +
      `PlayerChooseInitialServerEvent`) **plus** the `limbo/` verifier plugin that
      holds clients, checks behaviour, and signals `australis:verify`; the proxy
      promotes the IP and moves them to a real server. Fixed the bug where
      post-login auto-verify disabled routing (verify now happens on reaching a
      *real* backend). Unit-tested; needs a live MC network for full E2E.
      Native Netty-level in-proxy checks remain a future option.

## Phase 3 — Edge + feedback loop + XDP ✅
**Deliverable:** origin hiding + in-kernel drop of convicted IPs on a free VM, via
nftables (default) **and** a line-rate XDP/eBPF filter (opt-in), both fed by the
plugin's conviction feedback loop.
- [x] TCP forwarder with PROXY protocol v2 (Go) — header validated
- [x] Feedback agent: plugin → kernel nftables blocklist (Go) — tested live
- [x] Feedback client in the plugin (HTTP push, de-duplicated)
- [x] nftables ruleset (blocklist sets + SYN rate fallback) — validates
- [x] systemd units + one-command `install-edge.sh` (Oracle free tier)
- [x] Origin-lockdown instructions (docs/DEPLOYMENT.md)
- [x] **Own XDP/eBPF filter** (`edge/xdp/`, cilium/ebpf) — per-source SYN drop +
      expiring convicted-IP blocklist at the NIC; loader pins the map; agent
      `-xdp-map` wires the feedback loop to it. Validated live (700k+ SYNs/4s).
- [ ] Deeper in-XDP MC-protocol/VarInt validation + amplification source-port drop

## Phase 4 — Packaging & distribution ✅
- [x] Gradle wrapper committed; `go` module builds
- [x] GitHub Actions CI (build plugin + edge, artifacts, release on tag)
- [x] LICENSE (MIT), CONTRIBUTING, SECURITY
- [x] Quick-start for Mode A + Mode B (docs/QUICKSTART.md)
- [x] Honest capability matrix front-and-centre (README + ARCHITECTURE §6)
- [ ] Publish to Modrinth + Hangar (needs your accounts/tokens)
- [ ] Docs site on Cloudflare Pages (free)

## Phase 5 — Optional hosted edge (design)
See `docs/HOSTED-EDGE.md`.
- [x] Design for a managed anycast/edge (the only thing that costs money)
- [ ] Build it (only if there's demand; keep the core free forever)
- [ ] Optional signup integration with the the storefront

## Cross-cutting
- [x] Prometheus `/metrics` on the plugin (Velocity + Paper) and the edge
      forwarder — point Prometheus at them; a Grafana dashboard is just a panel set
- [x] BungeeCord/Waterfall port of the plugin — `bungee/` (reuses `common/`;
      exploits Bungee's cancellable PreLogin + real ping caching)
- [ ] Paper-direct mode (servers without a proxy)
- [ ] Bedrock/RakNet edge (Upioti XDP filter)
- [ ] IPv6 in the XDP path (current open-source filters are IPv4-only)
- [x] Self-attack test lab (your infra only) for regression testing —
      `testkit/lab.sh` (scenario matrix → summary table; baseline-vs-protected diff)
