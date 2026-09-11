# Australis

**Free, self-hosted DDoS/bot protection for Minecraft servers.** A TCPShield-style
protection stack you run yourself — no monthly bill, origin IP hidden, and it
actually stops the attacks servers get hit with.

> **Status: working v1.** The Velocity/Bungee/Paper plugins, the Go edge
> (forwarder + nftables feedback loop), and the **XDP/eBPF line-rate filter** are
> all built, tested, and live-validated (the XDP filter dropped 700k+ SYNs/4s and
> agent-driven bans at the NIC on a test box). XDP is opt-in (`XDP=1`) since it
> needs a supported NIC/kernel; nftables is the default. See `docs/`.

---

## The one-paragraph pitch

Most Minecraft attacks aren't 300 Gbps floods — they're **bot joins, ping
floods, SYN floods, and malformed-packet spam** that exhaust your CPU and main
thread, not your bandwidth. Australis stops these with two things working
together: a **Velocity/Paper plugin** that verifies real players and rate-limits
abuse, and a **self-hosted edge** (a cheap/free VM) that hides your origin IP and
drops convicted attackers **in the kernel** via nftables. For the rarer
true-volumetric case, you ride a **free anycast upstream** (playit.gg /
TCPShield-free) that already owns the big pipes. Result: real protection, $0.

## How it actually blocks attacks (the short version)

Honest status: ✅ = shipped & tested today · ⚠️ = partial/dependent · 🚧 = planned.

| Attack | Stopped by | Status |
|---|---|---|
| Bot join flood | Plugin verification + per-IP limits, convicted IPs pushed to the edge | ✅ shipped |
| Ping / MOTD flood | Plugin ping cache + per-IP ping tracking | ✅ shipped |
| Any convicted repeat abuser | **Edge nftables kernel drop** via the feedback loop | ✅ shipped (live-validated) |
| Origin-IP exposure | Edge forwarder (PROXY v2) / tunnel hides it | ✅ shipped |
| SYN flood on the game port | **XDP** per-source SYN drop at the NIC (nftables fallback) | ✅ shipped (opt-in) |
| Convicted IP, at the NIC | **XDP** blocklist drop via the feedback loop (or nftables) | ✅ shipped |
| Malformed / crafted packets | **XDP** drops truncated TCP, bad flag combos (NULL/XMAS/SYN-FIN/SYN-RST) and privileged-source-port SYNs at the NIC; stream-deep VarInt validation runs in L2 | ✅ shipped |
| Volumetric bigger than your uplink | Free anycast upstream (playit.gg) or a scrubber — **cannot** be stopped on-box; the flood is dropped upstream of your NIC by definition | ⚠️ off-box (physics) |

**What's shipped:** two L3/L4 paths — **nftables** (default, everywhere) and the
**XDP/eBPF** filter (`edge/xdp/`, opt-in with `XDP=1`) which drops SYN floods and
convicted IPs *at the NIC driver* at line rate (validated: 700k+ SYNs dropped in
4s on a test box). Both are fed by the plugin's conviction → agent → kernel
feedback loop. Deeper in-kernel MC-protocol/VarInt validation is still future. And
a *single* self-hosted edge can't absorb a flood bigger than its uplink — no free
tool can; that's what the anycast upstream is for. Full detail:
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) and
[`docs/THREAT-MODEL.md`](docs/THREAT-MODEL.md).

## Two ways to deploy (both $0)

- **Mode A — Zero-ops:** install the plugin + point players at a free playit.gg /
  TCPShield-free address. Origin hidden, volumetric absorbed upstream, plugin
  does all L7. Nothing else to host.
- **Mode B — Self-edge:** run a free **Oracle Cloud** VM as your own edge with
  the **TCP forwarder + nftables feedback loop** → origin hiding and in-kernel
  dropping of convicted attackers, still free. Add `XDP=1` to enable the **XDP
  filter** for line-rate SYN/blocklist drop at the NIC (needs a supported NIC).

Step-by-step: [`docs/DEPLOYMENT.md`](docs/DEPLOYMENT.md).

## Repo layout

```
docs/                 The plan
  ARCHITECTURE.md     How we actually block attacks (read this first)
  THREAT-MODEL.md     Every attack + the exact layer that kills it
  DEPLOYMENT.md       Stand it up on free infra (Mode A / Mode B)
  ROADMAP.md          Build order, phase by phase
common/               Shared protection logic (gg.australis.core) — one source of truth
plugin/               Velocity plugin (Layer 2) — for networks behind a Velocity proxy
bungee/               BungeeCord/Waterfall plugin (Layer 2) — for Bungee-based networks
paper/                Paper/Spigot plugin — runs directly on a backend server
limbo/                Paper limbo verifier — proves real clients, signals the proxy
edge/cmd/forwarder/   Edge TCP forwarder (Layer 0 self-mode) — built + tested
edge/cmd/agent/       Feedback agent: plugin → nftables kernel drop — built + tested
edge/nftables/        nftables ruleset (blocklist + SYN fallback) — default L3/L4 layer
edge/xdp/             Kernel XDP/eBPF filter (Layer 1) — built + tested (opt-in, line-rate)
edge/cmd/xdp-loader/  XDP loader (cilium/ebpf) — attaches the filter, pins the blocklist map
testkit/              floodtest — MC load/attack harness (run from your own box)
```

**Which plugin?** **Velocity** network → `plugin/`. **BungeeCord/Waterfall**
network → `bungee/`. Single **Paper** server with no proxy →
`paper/`. All three share the exact same tested protection logic
(`common/gg.australis.core`).

## Status

Working, and validated end-to-end:
- **Layer 2 plugin (Java):** attack detector, per-IP connection/ping/login rate
  limiting, connect/disconnect churn detection, status-ping cache, bot
  verification (reconnect challenge, attack-gated), verified-IP allowlist,
  `/australis` admin command, live config reload, and the edge feedback client.
- **Layer 1/0 edge (Go):** TCP forwarder with PROXY protocol v2 (validated —
  emits a byte-correct header), and a feedback agent that drops convicted IPs
  into a live nftables set (validated end-to-end), plus systemd units, nftables
  ruleset, and a one-command `install-edge.sh`.
- **Layer 1 XDP (Go, cilium/ebpf):** our own eBPF filter — per-source SYN-flood
  drop on the game port + an expiring convicted-IP blocklist, both at the NIC
  driver (line-rate). Loader pins the blocklist map so the agent drops IPs there.
  Live-validated on a test box (700k+ SYNs dropped in 4s). Opt-in (`XDP=1`); see
  `edge/xdp/`.

- **Deep verification:** limbo routing (`LimboRouter` + `australis:verify` plugin
  channel) that sends unverified players to a limbo backend first.
- **Tested:** 30 JUnit tests on the protection logic + Go tests on the edge, all
  passing; the feedback loop and PROXY v2 header were verified live. See
  [`docs/TESTING.md`](docs/TESTING.md).

Next: native Netty-level (Sonar-style) checks, Bedrock/BungeeCord support,
IPv6 XDP. See [`docs/ROADMAP.md`](docs/ROADMAP.md). Quick start:
[`docs/QUICKSTART.md`](docs/QUICKSTART.md).

## Install (plug and play)

No toolchain needed — grab prebuilt artifacts from [Releases](../../releases):

- **Plugin (the common case):** download the jar for your platform —
  `australis-velocity-*.jar` (Velocity), `australis-bungee-*.jar`
  (BungeeCord/Waterfall), or `australis-paper-*.jar` (a single Paper server with
  no proxy) — drop it in `plugins/`, start once, then edit
  `plugins/australis/config.yml`. Done. Sane defaults work out of the box.
- **Edge (Mode B, optional):** on a cheap/free VM run one command —
  `sudo ORIGIN=<your-server-ip>:25565 ./edge/install-edge.sh` (add `XDP=1` for the
  line-rate kernel filter). It sets up the forwarder + nftables + agent (+ XDP)
  and prints the `edge:` block to paste into the plugin config. Full walkthrough:
  [`docs/QUICKSTART.md`](docs/QUICKSTART.md).

## Build (from source)

```bash
# Plugin (needs JDK 17+)
cd plugin && ./gradlew shadowJar   # -> build/libs/australis-velocity-0.2.0.jar

# Edge (needs Go 1.24+)
cd edge && go build ./cmd/...
```
Drop the plugin jar in your Velocity `plugins/` folder. CI builds both on every
push (`.github/workflows/build.yml`) and attaches them to tagged releases.

## Prior art worth studying (don't reinvent)
- **Sonar** — open-source Velocity/Bungee/Paper antibot (L7 verification).
- **Outfluencer/Minecraft-XDP-eBPF** — Java-edition XDP filter (Layer 1).
- **Upioti/minecraft-bedrock-xdp-ebpf** — Bedrock/RakNet XDP filter.

## Legal
Only ever test protection against infrastructure **you own**. Attacking servers
you don't control is a crime everywhere. This project is for **defense**.

## License
[MIT](LICENSE). Check the licenses of any vendored XDP filters before
redistributing them alongside Australis.
