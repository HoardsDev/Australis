# Australis — Testing

What's under automated test, what was verified live, and what still needs a real
Minecraft environment.

## Automated tests (run in CI on every push)

### Plugin — JUnit (`plugin/src/test/java`)
The protection-logic classes are pure JDK (no Velocity dependency) precisely so
they can be unit-tested. **30 tests, all passing:**
- `ConnectionRateLimiterTest` — under/over limit, temp-ban, per-IP isolation,
  connection vs ping independence, window roll, pruning.
- `LoginThrottleTest` — attempt limit, churn detection, window reset, pruning.
- `AttackDetectorTest` — threshold trip, calm below threshold, cooldown expiry.
- `VerificationManagerTest` — disabled/allowlist/verified bypass, attack-gated
  challenge, deny-then-allow on reconnect, challenge re-issue after window,
  force verify/unverify.
- `LimboRouterTest` — routing decision across enabled/verified/attack states.

Run locally:
```bash
cd plugin && ./gradlew test
```

### Edge — Go (`edge/cmd/**/*_test.go`)
- `proxyv2_test.go` — PROXY protocol v2 header for IPv4 (28 bytes) and IPv6
  (52 bytes): signature, version/command, family/proto, length, address bytes,
  and rejection of non-TCP addresses.
- `agent main_test.go` — the feedback API: auth required, wrong token rejected,
  valid IPv4/IPv6 routed to the right nft set, invalid IP rejected, ban clamped
  to max, non-POST rejected. Uses an injected `nft` runner (no real nftables).

Run locally:
```bash
cd edge && go test ./...
```

## Verified live (manual, in this environment)
- **Feedback loop end-to-end:** loaded the real `nftables/australis.nft` ruleset,
  ran the agent, POSTed a block, and confirmed the IP appeared in the live
  kernel `inet australis` set with the expected timeout; unauthorized→401,
  invalid IP→400.
- **Forwarder PROXY v2:** ran the forwarder to a capture listener and confirmed
  the emitted header bytes match the spec exactly.
- **nftables ruleset:** `nft -c -f` validates.
- **Installer:** `bash -n install-edge.sh` passes.

### Load/attack harness (`testkit/`)
`floodtest` reproduces the real attack vectors (ping flood, bot join flood, conn
flood, reconnect challenge) so you can measure Australis against a live proxy.
Its protocol helpers are unit-tested and the ping path was validated end-to-end
against a mock status server. **Run it from your own machine against a target you
own/are authorized to test** (e.g. test-net) — see `testkit/README.md`.

## Still needs a real Minecraft environment (can't be done in this sandbox)
The Velocity-facing plugin glue compiles in CI (the Velocity API repo is not
reachable from the dev sandbox, so CI is its first full compile). These need a
live proxy + client/bot to exercise:
- The event pipeline against a real client handshake/login/ping.
- The **limbo** flow end-to-end (needs a NanoLimbo/Sonar backend and the
  `australis:verify` plugin message).
- Load behaviour under a real bot swarm — test **only against your own server**.

## Legal
Only run attack/load tests against infrastructure you own or are authorized to
test.
