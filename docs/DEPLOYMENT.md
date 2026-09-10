# Australis — Deployment Guide (100% Free)

Two supported shapes. Pick one. Both cost **$0**.

- **Mode A — Zero-ops** (easiest): players connect through a free anycast tunnel
  (playit.gg / TCPShield-free). You only install the plugin. Origin hidden,
  volumetric absorbed upstream, all L7 done by the plugin.
- **Mode B — Self-edge** (most control): you run a free **Oracle Cloud** VM as
  your own edge with the **TCP forwarder + nftables feedback loop**. Origin
  hiding, and in-kernel dropping of every IP the plugin convicts, still free.
  Add `XDP=1` to also run the **XDP/eBPF filter** for line-rate SYN/blocklist drop
  at the NIC; nftables is the default when XDP is off.)

You can start on A and graduate to B later; the plugin is identical.

---

## Mode A — Zero-ops (plugin + free tunnel)

**You need:** a Velocity proxy, a free playit.gg (or TCPShield) account.

1. **Install the Australis plugin** on Velocity — drop the jar in `plugins/`,
   start once, edit `plugins/australis/config.yml` (see `plugin` module).
2. **Set up the tunnel:**
   - *playit.gg:* run the playit agent on the proxy host; it gives you an
     address like `something.joinmc.link`. Point your `server-icon`/DNS/players
     at that address. Your real IP is never exposed.
   - *TCPShield free:* add your backend in their panel, get a
     `*.tcpshield.com`/custom CNAME, players connect there.
3. **Lock the origin:** firewall the proxy so the game port only accepts traffic
   from the tunnel/edge (see "Origin lockdown" below). This closes the
   "attacker found my real IP" hole.
4. Done. The plugin does all L7 verification; the tunnel hides IP + absorbs
   volumetric.

**Result:** Free. Stops L7 fully. Volumetric handled by the anycast upstream.
No XDP (you don't control the tunnel's kernel), which is fine — the tunnel
provider does their own L3/L4 scrubbing.

---

## Mode B — Self-edge on a free Oracle Cloud VM

**You need:** an Oracle Cloud "Always Free" account.

### B1. Create the free VM
- Shape: **VM.Standard.A1.Flex** (ARM Ampere). Always-Free grants ~2 OCPU /
  12 GB (Oracle reduced this from 4/24 in 2025) and **10 TB/month egress** — far
  more than any home line, on a large backbone with baseline network protection.
- OS: Ubuntu 22.04+ or any modern distro with **nftables** (default on all
  current mainstream distros).
- **Open the game port** (25565/TCP for Java, 19132/UDP for Bedrock) in **both**
  the OCI **security list / NSG** *and* the host `ufw`. Oracle blocks it by
  default — this is the #1 "nobody can connect" gotcha.

> Note: ARM capacity in free regions can be scarce — retry, or use an AMD
> `E2.1.Micro` always-free VM (smaller, still works, lower pps headroom). The
> installer builds native binaries for whichever architecture you land on.

### B2. Install the edge (forwarder + agent + nftables) — one command
```bash
git clone https://github.com/Negativevibez/Australis-.git
cd Australis-/edge
sudo ORIGIN=<your-origin-ip>:25565 ./install-edge.sh
```
This builds the forwarder + agent, loads the nftables blocklist ruleset, installs
systemd units, and starts everything. It forwards clean traffic to your
**origin** using **PROXY protocol v2** (so the real player IP survives for the
plugin to rate-limit), and exposes a token-authed local API the plugin calls to
drop convicted IPs into the kernel nftables set. Players connect to the **Oracle
VM IP** → origin never exposed. The installer prints the exact `edge:` block to
paste into the plugin config.

Reach the origin over a private link (WireGuard/Tailscale) so `ORIGIN` is a
private address, not a second public IP.

### B3. Point the Australis plugin (Layer 2) at the edge
On your **origin** Velocity:
- Set `haproxy-protocol = true` under `[advanced]` in `velocity.toml` so it
  trusts the real client IP the forwarder sends via PROXY protocol v2.
- Fill the `edge:` section of `plugins/australis/config.yml` with the URL + token
  the installer printed, so the plugin pushes malicious IPs to the edge's kernel
  blocklist.

### B4. (Optional) XDP/eBPF line-rate filter
Australis ships its own XDP filter (`edge/xdp/`) that drops SYN floods per-source
and convicted IPs **at the NIC driver** (line rate), far cheaper than nftables.
It's opt-in because it needs a supported NIC/kernel (any modern Linux ≥ 5.15 with
a driver that supports XDP — virtio, ixgbe, i40e, mlx5, etc.). Enable it by
re-running the installer with `XDP=1`:
```bash
sudo XDP=1 ORIGIN=<your-origin-ip>:25565 ./install-edge.sh
```
This builds `/opt/australis/xdp-loader`, attaches the filter, pins the blocklist
map, and points the agent at it so convicted IPs are dropped at the NIC (nftables
stays on as a fallback). Verify with `sudo bpftool prog show | grep australis`.
Tune `SYN_PER_WINDOW`/`SYN_WINDOW`. If your NIC can't attach XDP, leave it off —
the nftables path from B2 covers L3/L4 enforcement.

### B5. Origin lockdown (critical, both modes)
Make the origin only reachable *through* the edge, so nobody can bypass Australis
by hitting your real IP directly:
```bash
# On the ORIGIN: allow game port only from the edge VM's IP
sudo ufw default deny incoming
sudo ufw allow from <EDGE_VM_IP> to any port 25565 proto tcp
sudo ufw allow ssh
sudo ufw enable
```
(If your origin IP is already public/known, rotate it — new ISP lease or move the
backend — after locking down, or the old IP stays attackable.)

**Result:** Free. Line-rate L3/L4 + L7 packet filtering at the edge, full L7
verification at the plugin, IP hidden, feedback loop active.

---

## Deep verification (optional): route bots through a limbo

The reconnect challenge stops dumb flood bots (they never reconnect). Smarter
bots that *do* reconnect are caught by sending **not-yet-verified** players to a
lightweight **limbo** backend first, which runs movement/keep-alive checks before
they can reach the real server. This is attack-gated by default, so real players
are untouched in normal operation.

1. Run a small **Paper** server as the limbo and drop in the **`australis-limbo`**
   jar (`limbo/`, built by CI / on the `builds` branch). It holds each arriving
   client briefly, watches for real-client behaviour (a movement/look packet),
   and signals the proxy when they pass. Register it in `velocity.toml`:
   ```
   [servers]
   limbo = "127.0.0.1:30066"
   ```
   Tune `plugins/AustralisLimbo/config.yml` (`min-hold-millis`, `require-movement`,
   `fail-after-millis`) if needed.
2. In the proxy's `plugins/australis/config.yml`:
   ```yaml
   verification:
     limbo:
       enabled: true
       only-during-attack: true    # false = always route unverified players via limbo
       server: "limbo"             # must match the velocity.toml server name
       fallback-server: ""         # blank = first non-limbo server
   ```
3. Flow: unverified player → routed to `limbo` → `australis-limbo` verifies →
   sends `australis:verify` → the proxy marks the IP verified and moves them to a
   real server. Dumb flood bots that never behave like a client are kicked from
   limbo and never reach your backend. (An IP is auto-trusted for
   `verified-ttl-millis` once it reaches a real server, so returning players skip
   limbo. Alternatively you can point `limbo` at NanoLimbo/Sonar if you prefer
   their checks — anything that emits `australis:verify` from the backend works.)

## Verifying it works
- **Plugin state:** `/australis stats` (needs `australis.admin`) — shows attack
  state, blocked counts, verified IPs, and edge pushes.
- **Edge enforcement (the feedback loop):** flood your *own* proxy with
  `testkit/floodtest` (see `docs/TESTING.md`), then confirm convicted IPs appear
  in the kernel set — `sudo nft list set inet australis blocklist` on the edge —
  and that a blocked source can no longer reach the game port. (This exact path
  is validated end-to-end in `docs/FINDINGS-live-test.md`.)
- **Forwarder health:** if you enabled `-metrics`, `curl http://127.0.0.1:9100/metrics`
  on the edge shows accepts/active/rejected counters.
- **Bot test:** use a *self-hosted* bot tool against your *own* test server only
  (never anyone else's — that's illegal). Confirm dumb flood bots never complete
  the reconnect challenge and that repeat offenders get pushed to the edge set.

> ⚠️ Only ever test against infrastructure you own. Attacking servers you don't
> control is a crime in every jurisdiction that matters.

---

## Observability (optional)

Both the plugin and the edge forwarder can expose Prometheus metrics — point
Prometheus at them and build a Grafana panel. Bind to loopback or a private link
(no auth; the counters aren't sensitive).

- **Plugin (Velocity/Paper):** set in `config.yml`:
  ```yaml
  metrics:
    enabled: true
    bind: "127.0.0.1:9110"
  ```
  Scrape `http://127.0.0.1:9110/metrics` — `australis_*` counters (connections
  seen/blocked, pings, logins throttled, verification challenges/passes, edge
  pushes, attacks detected) plus `australis_under_attack` / `australis_verified_ips`.
  `/health` returns `ok`.
- **Edge forwarder:** run with `-metrics 127.0.0.1:9100` — accept/active/rejected
  counters.

## Cost recap
| Item | Mode A | Mode B |
|---|---|---|
| Edge / tunnel | playit/TCPShield free | Oracle Always-Free VM |
| L3/L4 filtering | n/a (provider scrubs) | nftables + XDP line-rate (free) |
| Plugin | free | free |
| Bandwidth | free (upstream) | 10 TB/mo free |
| **Monthly total** | **$0** | **$0** |
