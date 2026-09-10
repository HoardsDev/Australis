# Australis — Testing

What's under automated test, what was verified live, and what still needs a real
Minecraft environment.

## Automated tests (run in CI on every push)

### Plugin — JUnit (`plugin/src/test/java`) — **45 tests, all passing**
The protection-logic classes are pure JDK (no Velocity dependency) precisely so
they can be unit-tested.
- `ConnectionRateLimiterTest` — under/over limit, temp-ban, per-IP isolation,
  connection vs ping independence, window roll, pruning, non-incrementing peek.
- `LoginThrottleTest` — attempt limit, churn detection, window reset, pruning.
- `AttackDetectorTest` — threshold trip, calm below threshold, cooldown expiry.
- `VerificationManagerTest` — disabled/allowlist/verified bypass, attack-gated
  challenge, deny-then-allow on reconnect, challenge re-issue, force verify.
- `LimboRouterTest` — routing decision across enabled/verified/attack states.
- `ConfigSanitizerTest` — clamps bad config (zero/negative windows, thresholds).
- `AustralisConfigTest` — config parse/validate + malformed-YAML fallback.

```bash
cd plugin && ./gradlew test
```

### Paper plugin — JUnit (`paper/`) — **37 tests, all passing**
Shares `common/` protection logic + config sanitization with the Velocity plugin.
```bash
cd paper && ./gradlew test
```

### Edge — Go (`edge/**/*_test.go`)
- `forwarder proxyv2_test.go` — PROXY protocol v2 header for IPv4 (28 bytes) and
  IPv6 (52 bytes): signature, version/command, family/proto, length, address
  bytes, and rejection of non-TCP addresses.
- `forwarder limits_test.go` — per-IP + global connection caps, idempotent slot
  release, idle/slowloris teardown, drain-on-shutdown, and proxy passthrough —
  all over real loopback sockets.
- `agent main_test.go` — the feedback API: auth required, wrong token rejected,
  valid IPv4/IPv6 routed to the right nft set, invalid IP rejected, ban clamped
  to max, non-POST rejected. Uses an injected `nft` runner (no real nftables).

```bash
cd edge && go test ./...
```

## Verified live on real Linux (see `FINDINGS-live-test.md` for the full writeup)
- **Feedback loop end-to-end, with real enforcement:** ran the agent + forwarder
  as systemd services against the real `nftables/australis.nft` ruleset; a
  baseline connection through the forwarder reached the origin, then a POST to the
  agent added the source IP to the live kernel set and the **next connection was
  dropped by the kernel** (timeout). Unauthorized→401, invalid IP→400.
- **Hardened forwarder:** proxied round-trip, `/metrics` endpoint, per-IP cap, and
  graceful drain-on-SIGTERM all confirmed on a live box.
- **Plugin event pipeline:** exercised against the live the test network Velocity proxy
  (real handshake/login/ping floods via `testkit/floodtest`). This surfaced that
  Velocity's built-in `login-ratelimit` sits upstream of all plugin events — see
  `FINDINGS-live-test.md`; the plugin now detects/limits at `ConnectionHandshakeEvent`.
- **nftables ruleset:** `nft -c -f` validates (needs LF line endings — enforced by
  `.gitattributes`).

### Load/attack harness (`testkit/`)
`floodtest` reproduces the real attack vectors (ping flood, bot join flood, conn
flood, reconnect challenge) so you can measure Australis against a live proxy.
Its protocol helpers are unit-tested and the ping path was validated end-to-end
against a mock status server. **Run it from your own machine against a target you
own/are authorized to test** (e.g. test-net) — see `testkit/README.md`.

## Still to exercise
- The **limbo** flow end-to-end (needs a NanoLimbo/Sonar backend and the
  `australis:verify` plugin message) — routing logic is unit-tested; the live
  backend round-trip is not yet run.
- Distributed bot-swarm behaviour from **many** source IPs (a single/few-IP flood
  is absorbed by Velocity's own `login-ratelimit` before the plugin — see
  `FINDINGS-live-test.md`). Test **only against your own server**.
- The XDP filter is built + live-validated on a test VM (707k SYNs dropped in 4s;
  agent-driven ban → NIC drop). Still to exercise on production NIC hardware and
  to add deeper in-XDP protocol validation.

## Legal
Only run attack/load tests against infrastructure you own or are authorized to
test.
