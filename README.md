# Australis

**Free, self-hosted DDoS/bot protection for Minecraft servers.** A TCPShield-style
protection stack you run yourself — no monthly bill, origin IP hidden, and it
actually stops the attacks servers get hit with.

> Working codename **Australis** — rename to whatever you ship it as.
> This repo is the **plan + Phase 1 scaffold**. See `docs/` for the full design.

---

## The one-paragraph pitch

Most Minecraft attacks aren't 300 Gbps floods — they're **bot joins, ping
floods, SYN floods, and malformed-packet spam** that exhaust your CPU and main
thread, not your bandwidth. Those are **100% stoppable for free** with two
things working together: a **kernel-level XDP/eBPF filter** that drops bad
packets in the NIC at tens of millions/sec, and a **Velocity plugin** that
verifies real players and rate-limits abuse. For the rarer true-volumetric case,
you ride a **free anycast upstream** (playit.gg / TCPShield-free) that already
owns the big pipes. Result: real protection, $0.

## How it actually blocks attacks (the short version)

| Attack | Stopped by | Free? |
|---|---|---|
| Bot join flood | Plugin verification (limbo challenge) | ✅ |
| Ping/MOTD flood | Plugin ping cache + XDP rate-limit | ✅ |
| SYN flood / packet flood (up to line rate) | XDP `XDP_DROP` at NIC | ✅ |
| Malformed/crash packets | XDP protocol validation | ✅ |
| Origin-IP exposure | Edge/tunnel hides it | ✅ |
| Volumetric bigger than your uplink | Free anycast upstream (or paid scrubber) | ✅* |

\* Anycast upstream distributes it for free; a *single self-hosted edge* can't
absorb an arbitrarily huge flood — no free tool can. We say so plainly. Full
detail: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) and
[`docs/THREAT-MODEL.md`](docs/THREAT-MODEL.md).

## Two ways to deploy (both $0)

- **Mode A — Zero-ops:** install the plugin + point players at a free playit.gg /
  TCPShield-free address. Origin hidden, volumetric absorbed upstream, plugin
  does all L7. Nothing else to host.
- **Mode B — Self-edge:** run a free **Oracle Cloud** VM as your own edge with
  the **XDP filter** + a TCP forwarder → line-rate L3/L4 + L7 filtering *and* IP
  hiding, still free.

Step-by-step: [`docs/DEPLOYMENT.md`](docs/DEPLOYMENT.md).

## Repo layout

```
docs/                 The plan
  ARCHITECTURE.md     How we actually block attacks (read this first)
  THREAT-MODEL.md     Every attack + the exact layer that kills it
  DEPLOYMENT.md       Stand it up on free infra (Mode A / Mode B)
  ROADMAP.md          Build order, phase by phase
plugin/               Velocity plugin (Layer 2) — Phase 1 scaffold, buildable
edge/xdp/             Kernel XDP/eBPF filter (Layer 1) — integration plan
edge/proxy/           Edge TCP forwarder (Layer 0 self-mode) — integration plan
```

## Status

Phase 1 scaffold: connection + ping rate limiting, config, plugin lifecycle.
Next: Netty early-rejection, bot verification (Phase 2), XDP edge + feedback
loop (Phase 3). See [`docs/ROADMAP.md`](docs/ROADMAP.md).

## Build the plugin

```bash
cd plugin
./gradlew build      # produces build/libs/australis-velocity-0.1.0-SNAPSHOT.jar
```
(Needs JDK 17+. Drop the jar in your Velocity `plugins/` folder.)

## Prior art worth studying (don't reinvent)
- **Sonar** — open-source Velocity/Bungee/Paper antibot (L7 verification).
- **Outfluencer/Minecraft-XDP-eBPF** — Java-edition XDP filter (Layer 1).
- **Upioti/minecraft-bedrock-xdp-ebpf** — Bedrock/RakNet XDP filter.

## Legal
Only ever test protection against infrastructure **you own**. Attacking servers
you don't control is a crime everywhere. This project is for **defense**.

## License
TBD (MIT recommended for adoption). Check licenses of any vendored XDP filters
before redistributing.
