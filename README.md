# Australis

**Free, self-hosted DDoS/bot protection for Minecraft servers.** A TCPShield-style
protection stack you run yourself — no monthly bill, origin IP hidden, and it
actually stops the attacks servers get hit with.

> Working codename **Australis** — rename to whatever you ship it as.
> **Status: working v1.** The Velocity/Paper plugin and the Go edge (forwarder +
> nftables feedback loop) are built, tested, and live-validated. The XDP/eBPF
> line-rate filter is **not yet implemented** — it's the planned performance
> upgrade (see the honest matrix below). See `docs/` for the full design.

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
| SYN / packet flood on the game port | nftables SYN rate-limit (basic fallback) → XDP line-rate | ⚠️ basic today · 🚧 XDP planned |
| Malformed / crash packets | Plugin protocol handling → XDP VarInt/protocol validation at NIC | 🚧 XDP planned |
| Volumetric bigger than your uplink | Free anycast upstream (or paid scrubber) | ⚠️ upstream-dependent |

**Be clear on what's shipped:** the L3/L4 layer today is **nftables** — a proven
in-kernel blocklist drop (the plugin convicts an IP → the edge agent drops it at
the NIC) plus a basic SYN rate-limit. The **XDP/eBPF** line-rate filter
(`edge/xdp/`) is **not implemented yet** — it's the planned performance upgrade.
And a *single* self-hosted edge can't absorb a flood bigger than its uplink — no
free tool can; that's what the anycast upstream is for. Full detail:
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) and
[`docs/THREAT-MODEL.md`](docs/THREAT-MODEL.md).

## Two ways to deploy (both $0)

- **Mode A — Zero-ops:** install the plugin + point players at a free playit.gg /
  TCPShield-free address. Origin hidden, volumetric absorbed upstream, plugin
  does all L7. Nothing else to host.
- **Mode B — Self-edge:** run a free **Oracle Cloud** VM as your own edge with
  the **TCP forwarder + nftables feedback loop** → origin hiding and in-kernel
  dropping of convicted attackers, still free. (Add the **XDP filter** on top for
  line-rate L3/L4 once it lands — planned; the nftables path works today.)

Step-by-step: [`docs/DEPLOYMENT.md`](docs/DEPLOYMENT.md).

## Repo layout

```
docs/                 The plan
  ARCHITECTURE.md     How we actually block attacks (read this first)
  THREAT-MODEL.md     Every attack + the exact layer that kills it
  DEPLOYMENT.md       Stand it up on free infra (Mode A / Mode B)
  ROADMAP.md          Build order, phase by phase
common/               Shared protection logic (gg.australis.core) — one source of truth
plugin/               Velocity plugin (Layer 2) — for networks behind a proxy
paper/                Paper/Spigot plugin — runs directly on a backend server
edge/cmd/forwarder/   Edge TCP forwarder (Layer 0 self-mode) — built + tested
edge/cmd/agent/       Feedback agent: plugin → nftables kernel drop — built + tested
edge/nftables/        nftables ruleset (blocklist + SYN fallback) — the L3/L4 layer today
edge/xdp/             Kernel XDP/eBPF filter (Layer 1) — integration plan (not built yet)
testkit/              floodtest — MC load/attack harness (run from your own box)
```

**Which plugin?** Running a **Velocity** network → use `plugin/`. Running a
single **Paper** server (like a Paper server) with no proxy → use `paper/` (drop the
jar in `plugins/`). Both share the exact same tested protection logic.

## Status

Working, and validated where the sandbox allowed:
- **Layer 2 plugin (Java):** attack detector, per-IP connection/ping/login rate
  limiting, connect/disconnect churn detection, status-ping cache, bot
  verification (reconnect challenge, attack-gated), verified-IP allowlist,
  `/australis` admin command, live config reload, and the edge feedback client.
- **Layer 1/0 edge (Go):** TCP forwarder with PROXY protocol v2 (validated —
  emits a byte-correct header), and a feedback agent that drops convicted IPs
  into a live nftables set (validated end-to-end), plus systemd units, nftables
  ruleset, and a one-command `install-edge.sh`.
- **Layer 1 XDP:** integration plan around the open-source Minecraft XDP filters
  (see `edge/xdp/`).

- **Deep verification:** limbo routing (`LimboRouter` + `australis:verify` plugin
  channel) that sends unverified players to a limbo backend first.
- **Tested:** 30 JUnit tests on the protection logic + Go tests on the edge, all
  passing; the feedback loop and PROXY v2 header were verified live. See
  [`docs/TESTING.md`](docs/TESTING.md).

Next: native Netty-level (Sonar-style) checks, Bedrock/BungeeCord support,
IPv6 XDP. See [`docs/ROADMAP.md`](docs/ROADMAP.md). Quick start:
[`docs/QUICKSTART.md`](docs/QUICKSTART.md).

## Build

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
