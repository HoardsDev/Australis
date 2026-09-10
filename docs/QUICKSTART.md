# Australis — Quick Start

## Mode A — Zero-ops (5 minutes): plugin + free tunnel

1. **Build the plugin** (or grab the jar from Releases):
   ```bash
   cd plugin && ./gradlew shadowJar
   # -> build/libs/rampart... no: build/libs/australis-velocity-0.2.0.jar
   ```
2. Drop the jar into your Velocity `plugins/` folder and start the proxy once.
3. Edit `plugins/australis/config.yml` if you want (defaults are sane).
4. Point players through a free tunnel that hides your IP:
   - **playit.gg** — run the agent, share the `*.joinmc.link` address.
   - or **TCPShield free** — add your backend, use their CNAME.
5. Firewall the origin so the game port only accepts the tunnel (docs/DEPLOYMENT.md).

You now have full L7/bot protection + IP hiding, free.

## Mode B — Self-edge (20 minutes): your own free Oracle VM

On a free **Oracle Cloud always-free VM** (kernel ≥ 5.15):

```bash
git clone https://github.com/Negativevibez/Australis-.git
cd Australis-/edge
sudo ORIGIN=10.8.0.1:25565 ./install-edge.sh    # 10.8.0.1 = your origin over WireGuard
```

The installer builds the forwarder + agent, loads the nftables blocklist, and
starts everything. It prints the `edge:` block to paste into the plugin config.
Then:

1. On the **origin** Velocity, set `proxy-protocol = true` in `velocity.toml`.
2. In `plugins/australis/config.yml`, fill the `edge:` section with the printed
   URL + token.
3. Lock down the origin (docs/DEPLOYMENT.md → Origin lockdown).
4. (Recommended) Add the XDP filter for line-rate L3/L4: `edge/xdp/README.md`.

Players connect to the Oracle VM's IP; your origin stays hidden; abusers the
plugin convicts get dropped at the edge kernel automatically.

## Verify it's working
```
/australis stats     # in-game or console (needs australis.admin)
```
Shows attack state, blocked counts, verified IPs, and edge pushes.

> ⚠️ Only test attacks against infrastructure you own.
