# Australis — Build Roadmap

Ordered by value-per-effort. Each phase is independently useful — ship as you go.
`[x]` = done in this repo, `[~]` = partial / API-limited (see note), `[ ]` = todo.

## Phase 1 — L7 plugin core ✅
**Deliverable:** a Velocity plugin that already stops most attacks.
- [x] Project scaffold (Gradle wrapper, plugin main, config)
- [x] Per-IP connection rate limiter (sliding window + temp-ban + self-pruning)
- [x] Per-IP login rate limiter + connect/disconnect churn detection
- [x] Global attack detector (gates aggressive defences to real attacks)
- [x] Status/ping response cache + per-IP ping rate limit
- [x] Config schema + live hot reload (`/australis reload`)
- [x] Metrics counters + `/australis stats` command
- [~] Netty early-rejection *below* the public API — the public event pipeline
      (PreLogin) already rejects pre-auth; true Netty-pipeline rejection is the
      deep path shared with Phase 2 below.

## Phase 2 — Bot verification ✅ (API-level) / [ ] (deep)
**Deliverable:** near-total bot-join immunity.
- [x] Reconnect challenge (attack-gated) — dumb flood bots never reconnect
- [x] Verified-IP allowlist with TTL; auto-verify on successful session
- [x] Operator allowlist + manual `/australis verify|unverify`
- [x] Pass → forward; fail → (via limiters) drop + feed to edge blocklist
- [~] **Deep:** limbo *routing* is built (`LimboRouter` + `australis:verify`
      plugin channel + PlayerChooseInitialServerEvent) and unit-tested; it needs
      a limbo backend (NanoLimbo/Sonar) to run the actual movement/keep-alive
      checks. Native Netty-level checks in-proxy remain a future option.

## Phase 3 — Edge + XDP + feedback loop ✅
**Deliverable:** line-rate kernel filtering + origin hiding on a free VM.
- [x] TCP forwarder with PROXY protocol v2 (Go) — header validated
- [x] Feedback agent: plugin → kernel nftables blocklist (Go) — tested live
- [x] Feedback client in the plugin (HTTP push, de-duplicated)
- [x] nftables ruleset (blocklist sets + SYN rate fallback) — validates
- [x] systemd units + one-command `install-edge.sh` (Oracle free tier)
- [x] Origin-lockdown instructions (docs/DEPLOYMENT.md)
- [ ] Vendor/build the open-source Minecraft XDP filter + wire agent to its map
      (nftables set is the working interim; XDP map is the perf upgrade)

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
- [ ] BungeeCord/Waterfall port of the plugin
- [ ] Paper-direct mode (servers without a proxy)
- [ ] Bedrock/RakNet edge (Upioti XDP filter)
- [ ] IPv6 in the XDP path (current open-source filters are IPv4-only)
- [ ] Self-attack test lab (your infra only) for regression testing
