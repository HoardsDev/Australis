# Australis — Build Roadmap

Ordered by value-per-effort. Each phase is independently useful — ship as you go.

## Phase 1 — L7 plugin core (biggest bang, start here)
**Deliverable:** a Velocity plugin that already stops most attacks.
- [x] Project scaffold (Gradle, plugin main, config) — in this repo
- [ ] Netty early-rejection handler (kill bad connections before player object)
- [ ] Per-IP connection rate limiter (sliding window) — *starter included*
- [ ] Per-IP login rate limiter + connect/disconnect churn detection
- [ ] Handshake / protocol-state validation (reject out-of-order, bad next-state)
- [ ] Status/ping response cache + per-IP ping rate limit
- [ ] Config schema + hot reload
- [ ] Metrics (counters for dropped/verified) + `/australis stats` command
> Study `Sonar` (open-source) before/while building — it solves this exact space.

## Phase 2 — Bot verification (limbo challenge)
**Deliverable:** near-total bot-join immunity.
- [ ] Hold new joiners in a limbo/verification state (don't forward to backend yet)
- [ ] Physics/movement challenge (gravity, position packets bots skip)
- [ ] Keep-alive + transaction/response challenge
- [ ] Configurable verification difficulty + allowlist for known-good IPs
- [ ] Pass → forward to backend; fail → drop + feed to blocklist

## Phase 3 — Edge + XDP integration (adds L3/L4 + IP hiding, self-mode)
**Deliverable:** line-rate kernel filtering + origin hiding on a free VM.
- [ ] Vendor/build the open-source Minecraft XDP filter (Java + Bedrock)
- [ ] systemd units + one-command `install-edge.sh` for Oracle free tier
- [ ] Thin TCP forwarder with PROXY protocol v2 (edge → origin)
- [ ] **Feedback bridge:** plugin writes malicious IPs into the XDP blocklist map
      (local API/socket on the edge, shared-token auth)
- [ ] Origin-lockdown helper script

## Phase 4 — Packaging & distribution
- [ ] Publish plugin to Modrinth + Hangar + GitHub Releases
- [ ] Docs site on Cloudflare Pages (free) — here Cloudflare is the right tool
- [ ] Quick-start for Mode A (5 min) and Mode B (20 min)
- [ ] Honest capability matrix front-and-center (copy from ARCHITECTURE §6)

## Phase 5 — Optional hosted edge (the only thing that ever costs money)
- [ ] Managed anycast/edge you host (bandwidth = real cost)
- [ ] Keep it opt-in / donation- or sponsor-funded so the core stays free forever
- [ ] Integrate signup with the existing the storefront if desired

## Cross-cutting
- [ ] BungeeCord/Waterfall port of the plugin (after Velocity is solid)
- [ ] Paper-direct mode (for servers without a proxy)
- [ ] IPv6 support in the XDP path (current open-source filters are IPv4-only)
- [ ] Test harness: self-attack lab (your infra only) for regression testing
