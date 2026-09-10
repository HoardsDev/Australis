# Australis Edge — Layer 0 (self-mode): TCP forwarder

In **Mode B** (self-hosted edge on a cheap/free VM), a forwarder relays incoming
traffic to your **origin** (the box running Velocity + the Australis plugin).
Players only ever see the edge IP, so the origin stays hidden.

> **The forwarder is built and shipped** at [`../cmd/forwarder`](../cmd/forwarder)
> (zero-dependency Go). `install-edge.sh` builds and runs it as a systemd service
> alongside the agent + nftables. This page is background/alternatives.

## What it does
- Preserves the **real client IP** to the origin via **PROXY protocol v2**, so the
  plugin can rate-limit per real IP. On the origin Velocity set
  `haproxy-protocol = true` under `[advanced]` in `velocity.toml`.
- Enforces a global connection cap, a per-source-IP cap, and idle/slowloris
  timeouts, so the edge box itself can't be exhausted (flags:
  `-max-conns`, `-max-conns-per-ip`, `-idle-timeout`).
- Optional `-metrics 127.0.0.1:PORT` exposes plaintext accept/active/rejected
  counters; graceful drain on SIGTERM.
- Encrypt edge→origin if it crosses the public internet (WireGuard/Tailscale is
  the simple free choice; then set `-origin` to the private tunnel IP).

## Run it
```bash
# via the installer (recommended):
sudo ORIGIN=10.8.0.1:25565 ../install-edge.sh
# or directly:
./forwarder -listen :25565 -origin 10.8.0.1:25565 -proxy-protocol \
            -max-conns 8192 -max-conns-per-ip 64 -idle-timeout 5m
```

## Alternatives (if you'd rather not run the Go forwarder)
- **HAProxy** (`mode tcp` + `send-proxy-v2`) — battle-tested, config-only.
- **Infrared** / **Gate** — Minecraft-aware Go proxies (also give you MOTD/host
  routing). Ensure they emit PROXY protocol v2 to the origin.

### HAProxy example (edge → origin over WireGuard)
```
frontend mc_in
    bind *:25565
    mode tcp
    default_backend mc_origin
backend mc_origin
    mode tcp
    server origin 10.8.0.1:25565 send-proxy-v2   # 10.8.0.1 = origin over WireGuard
```
Then on the origin Velocity set `haproxy-protocol = true`, and lock the origin so
25565 only accepts the edge/WireGuard IP (see `docs/DEPLOYMENT.md` → "Origin
lockdown").

## How the layers compose
- The forwarder (Layer 0) relays traffic and hides the origin, with the true
  client IP preserved.
- The plugin (Layer 2) does application-level verification and, via the feedback
  agent, pushes convicted abusers into the edge's kernel **nftables** blocklist.
- An XDP/eBPF filter (Layer 1, `../xdp/`) for line-rate junk-dropping is planned
  but **not shipped yet**; the nftables path handles enforcement today.
