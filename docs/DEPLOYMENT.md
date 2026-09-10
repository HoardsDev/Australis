# Australis — Deployment Guide (100% Free)

Two supported shapes. Pick one. Both cost **$0**.

- **Mode A — Zero-ops** (easiest): players connect through a free anycast tunnel
  (playit.gg / TCPShield-free). You only install the plugin. Origin hidden,
  volumetric absorbed upstream, all L7 done by the plugin.
- **Mode B — Self-edge** (most control): you run a free **Oracle Cloud** VM as
  your own edge with the **XDP filter** + a TCP forwarder. Line-rate L3/L4 + L7
  packet filtering *and* IP hiding, still free.

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
- OS: Ubuntu 22.04+ or any distro with **kernel ≥ 5.15** (required for the Java
  XDP filter's `bpf_timer`).
- Open the game port (25565/TCP for Java, 19132/UDP for Bedrock) in the OCI
  security list + `ufw`.

> Note: ARM capacity in free regions can be scarce — retry, or use an AMD
> `E2.1.Micro` always-free VM (smaller, still works, lower pps headroom).

### B2. Install the XDP/eBPF filter (Layer 1)
See `edge/xdp/README.md`. Summary:
- Java: vendor/build `Outfluencer/Minecraft-XDP-eBPF` (Rust + C), attach to the
  VM's NIC on the game port. Tune SYN rate, connection caps, port range.
- Bedrock: use `Upioti/minecraft-bedrock-xdp-ebpf`.
- The userspace loader must stay running (systemd unit provided in `edge/xdp/`).

### B3. Install the TCP forwarder (Layer 0 self-mode)
See `edge/proxy/`. A thin forwarder takes clean traffic that survived the XDP
filter and forwards it to your **origin** (home/backend) over an encrypted link,
using **PROXY protocol v2** so the real player IP survives for the plugin to
rate-limit. Players connect to the **Oracle VM IP** → origin never exposed.

### B4. Install the Australis plugin (Layer 2) on your origin Velocity
- Enable `proxy-protocol` in Velocity so it trusts the forwarded IPs from the edge.
- Configure the **feedback bridge** (`edge:` section of `config.yml`) with the
  edge VM's address + a shared token, so the plugin can push malicious IPs into
  the edge's XDP blocklist map.

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

## Verifying it works
- **Ping flood test:** run a status-ping loop from another host; confirm the edge
  XDP counters (Prometheus) show drops and the backend CPU stays flat.
- **Bot test:** use a *self-hosted* bot tool against your *own* test server only
  (never against anyone else's — that's illegal). Confirm bots stick in limbo and
  never reach backend; confirm their IPs land in the XDP blocklist.
- **SYN test:** `hping3 -S` against your own edge; confirm XDP SYN rate-limit
  drops and connection table stays healthy.

> ⚠️ Only ever test against infrastructure you own. Attacking servers you don't
> control is a crime in every jurisdiction that matters.

---

## Cost recap
| Item | Mode A | Mode B |
|---|---|---|
| Edge / tunnel | playit/TCPShield free | Oracle Always-Free VM |
| XDP filter | n/a (provider scrubs) | free (open-source) |
| Plugin | free | free |
| Bandwidth | free (upstream) | 10 TB/mo free |
| **Monthly total** | **$0** | **$0** |
