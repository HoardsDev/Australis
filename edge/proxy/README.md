# Australis Edge — Layer 0 (self-mode): TCP forwarder

In **Mode B** (self-hosted edge on a free Oracle VM), after the XDP filter drops
junk, a thin forwarder relays the surviving *valid* traffic to your **origin**
(the box running Velocity + the Australis plugin). Players only ever see the edge
IP, so the origin stays hidden.

## Requirements
- Preserve the **real client IP** to the origin so the plugin can rate-limit per
  IP → use **PROXY protocol v2** (Velocity supports it; enable `proxy-protocol`
  in `velocity.toml`).
- Encrypt edge→origin if it crosses the public internet (WireGuard tunnel is the
  simple free choice; then forward over the tunnel's private IP).
- Fail closed: if the origin is down, drop rather than leak.

## Simple starting options (pick one for Phase 3)
- **HAProxy** (`mode tcp` + `send-proxy-v2`) — battle-tested, config-only.
- **Infrared** / **Gate** — Minecraft-aware Go proxies (also give you MOTD/host
  routing for free).
- A ~150-line Go forwarder if you want zero external deps (planned:
  `forwarder.go`).

## HAProxy example (edge → origin over WireGuard)
```
# /etc/haproxy/haproxy.cfg  (on the edge VM)
frontend mc_in
    bind *:25565
    mode tcp
    default_backend mc_origin

backend mc_origin
    mode tcp
    server origin 10.8.0.1:25565 send-proxy-v2   # 10.8.0.1 = origin over WireGuard
```

Then in `velocity.toml` on the origin:
```toml
proxy-protocol = true
```
and lock the origin so 25565 only accepts the edge/WireGuard IP
(see `docs/DEPLOYMENT.md` → "Origin lockdown").

## Why this + XDP together
- XDP (Layer 1) throws away everything that isn't valid MC, at line rate.
- The forwarder (Layer 0) relays only what's left, with the true client IP.
- The plugin (Layer 2) does application-level verification and, via the feedback
  bridge, pushes abusers back down into XDP's kernel blocklist.
One system, three layers, $0.
