# Australis — Quick Start

## Mode A — Zero-ops (5 minutes): plugin + free tunnel

1. **Build the plugin** (or grab the jar from Releases / the `builds` branch):
   ```bash
   cd plugin && ./gradlew shadowJar
   # -> build/libs/australis-velocity-0.2.0.jar
   ```
2. Drop the jar into your Velocity `plugins/` folder and start the proxy once.
3. Edit `plugins/australis/config.yml` if you want (defaults are sane).
4. Point players through a free tunnel that hides your IP:
   - **playit.gg** — run the agent, share the `*.joinmc.link` address.
   - or **TCPShield free** — add your backend, use their CNAME.
5. Firewall the origin so the game port only accepts the tunnel (docs/DEPLOYMENT.md).

You now have full L7/bot protection + IP hiding, free.

## Mode B — Self-edge (20 minutes): your own free Oracle VM

On a free **Oracle Cloud always-free VM** (any modern Linux with nftables):

```bash
git clone https://github.com/Negativevibez/Australis-.git
cd Australis-/edge
sudo ORIGIN=10.8.0.1:25565 ./install-edge.sh    # 10.8.0.1 = your origin over WireGuard
```

The installer builds the forwarder + agent, loads the nftables blocklist, and
starts everything as systemd services. It prints the `edge:` block to paste into
the plugin config. Then:

1. **Open the game port** to the internet on this edge box — both in the cloud
   firewall (Oracle: the VCN **security list / NSG**) *and* the host: e.g.
   `sudo ufw allow 25565/tcp`. (Oracle VMs block it by default — this is the #1
   "why can't anyone connect" gotcha.)
2. On the **origin** Velocity, set `haproxy-protocol = true` under `[advanced]`
   in `velocity.toml` so it trusts the real client IP the forwarder sends.
3. In `plugins/australis/config.yml`, fill the `edge:` section with the printed
   URL + token.
4. Lock down the origin so it only accepts the edge (docs/DEPLOYMENT.md → Origin
   lockdown).

Players connect to the Oracle VM's IP; your origin stays hidden; abusers the
plugin convicts get dropped at the edge kernel (nftables) automatically.

> **Optional:** add `XDP=1` to the installer to also run the **XDP/eBPF filter**
> — per-source SYN-flood drop + convicted-IP blocklist at the NIC driver (line
> rate). Needs a supported NIC/kernel; nftables is the default when it's off. See
> `edge/xdp/README.md`.

## Verify it's working
```
/australis stats     # in-game or console (needs australis.admin)
```
Shows attack state, blocked counts, verified IPs, and edge pushes.

> ⚠️ Only test attacks against infrastructure you own.
