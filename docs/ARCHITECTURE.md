# Australis — Architecture & "How We Actually Block Attacks"

> Working codename: **Australis**. Rename freely.
> Goal: a **free**, **self-hosted** protection stack for Minecraft servers that
> defeats the attacks servers actually get hit with — bot floods, ping floods,
> protocol exploits, and packet/SYN floods — while hiding the origin IP.

---

## 1. The honest physics (read this first)

There is exactly **one** thing money buys that software cannot manufacture:
**bandwidth (the size of the pipe into your machine).**

Every attack falls into one of two buckets, and the distinction decides
everything:

| Bucket | What it exhausts | Can free software stop it? |
|---|---|---|
| **CPU / main-thread / connection-slot exhaustion** — bot joins, ping floods, malformed packets, SYN floods, protocol exploits | Your server's *compute* | ✅ **YES** — drop it cheaply before it costs anything |
| **Bandwidth saturation** — a flood bigger than your uplink (e.g. 100 Gbps into a 1 Gbps port) | Your *pipe* | ❌ Not by itself — the pipe is full before your code runs |

The mistake in my earlier framing was treating "DDoS" as one monolithic
"needs Tbps to stop" problem. It isn't. **The overwhelming majority of attacks
that take Minecraft servers offline are the first bucket** — and the first
bucket is *fully solvable for free*. The second bucket is rarer than people
think, and we handle it by **borrowing** big pipes from providers that already
give them away, instead of trying to build our own.

So the plan is not "manufacture Tbps for free." The plan is:

1. **Crush bucket #1 completely** with kernel-level packet filtering (XDP/eBPF)
   + application-level bot verification (the plugin). This is the 95% case.
2. **Survive bucket #2** by riding on a free/near-free upstream that already
   owns big pipes and anycast, and hiding the origin behind it.

That combination gives *effective, real-world* protection at **$0** for the
vast majority of servers — and a clear, honest upgrade path for the few that
face nation-state-sized floods.

---

## 2. The four attack patterns (and where each one dies)

Research consensus: four patterns cover almost everything a Minecraft server
sees. Here's the exact layer that kills each in the Australis stack.

| # | Attack | Bucket | Killed by | Layer |
|---|---|---|---|---|
| 1 | **Bot join flood** — thousands of fake clients complete real handshakes, walk into login, exhaust slots + main thread | CPU | **Plugin verification** (limbo challenge) + XDP connection tracking | L7 |
| 2 | **Status / MOTD ping flood** — hammering the server list ping | CPU | **XDP** (rate-limit status packets) + plugin ping cache | L7 |
| 3 | **L4 packet flood on the game port** — SYN flood, garbage TCP/UDP, invalid protocol | CPU (pps) up to line rate | **XDP** `XDP_DROP` at driver level (SYN rate-limit, protocol validation, VarInt check) | L3/L4 + L7 |
| 4 | **Volumetric uplink saturation** — flood larger than the pipe | Bandwidth | **Upstream edge** (anycast / big-pipe provider) hides origin & absorbs | L3/L4 |

Patterns 1–3 are handled *on your own hardware for free*. Only pattern 4 needs
external capacity — and even then we get it free (see §5).

---

## 3. The stack (layered defense)

```
                        ┌──────────────────────────────────────────────┐
   Players ───────────► │  LAYER 0 — EDGE (hides IP, absorbs volumetric) │
   connect to the       │  Free anycast/tunnel OR free-tier VM:          │
   edge address         │   • playit.gg (anycast, free)     OR           │
                        │   • TCPShield free tier           OR           │
                        │   • Oracle Cloud always-free VM (10TB egress)  │
                        │  Origin IP never exposed to players/attackers  │
                        └───────────────────────┬──────────────────────┘
                                                │  clean-ish traffic
                                                ▼
                        ┌──────────────────────────────────────────────┐
   (only if you own     │  LAYER 1 — XDP/eBPF FILTER (kernel, line rate) │
    the edge box, e.g.  │   • Runs on the edge VM's NIC (or the origin   │
    Oracle VM)          │     box if no separate edge)                   │
                        │   • XDP_DROP invalid packets in ~a few cycles  │
                        │   • SYN rate-limit (default 10 SYN / 3s / IP)  │
                        │   • Minecraft handshake / VarInt validation    │
                        │   • Drops amplification source ports           │
                        │   • Tens of millions of pps, sub-µs latency    │
                        └───────────────────────┬──────────────────────┘
                                                │  only valid MC traffic
                                                ▼
                        ┌──────────────────────────────────────────────┐
   Server owner's box   │  LAYER 2 — VELOCITY PROXY + AUSTRALIS PLUGIN     │
                        │   • Netty early rejection (before player obj)  │
                        │   • Per-IP connection + login rate limits      │
                        │   • Bot verification (limbo: movement/gravity) │
                        │   • Ping cache, join-leave spam detection      │
                        │   • Feedback loop → pushes bad IPs to Layer 1  │
                        └───────────────────────┬──────────────────────┘
                                                ▼
                            Backend Paper/Spigot servers (real gameplay)
```

**Key architectural idea — the feedback loop.** The plugin (Layer 2) *sees*
application-level abuse that the kernel can't judge (e.g. a client that passed
the handshake but then behaves like a bot). When it decides an IP is malicious,
it writes that IP into the XDP filter's in-kernel blocklist map (Layer 1). From
then on that attacker is dropped in a few CPU cycles at the NIC, costing
essentially nothing. **This turns expensive L7 detection into cheap L3/L4
enforcement.** That is the trick that makes a single modest box withstand
attacks that would normally require far more hardware.

---

## 4. Why Layer 1 (XDP/eBPF) is the game-changer

Normal filtering (iptables, or worse, application code) processes a packet
*after* the kernel has allocated memory and walked its network stack — so a
flood still burns CPU even when every packet is ultimately rejected. XDP runs
**in the NIC driver, before that allocation happens**:

- `XDP_DROP` discards a bad packet in a handful of CPU cycles.
- Sustains **millions to tens of millions of drops per second** on ordinary
  server hardware without the box falling over.
- Mitigation latency < 1 µs.

There are already **open-source, Minecraft-protocol-aware XDP filters** we build
on rather than reinvent:

- **Java Edition:** `Outfluencer/Minecraft-XDP-eBPF` — Rust userspace loader +
  C eBPF. Does SYN rate-limiting, in-kernel connection tracking, handshake /
  status / login inspection, drops invalid packets / bad VarInts / protocol
  violations, idle-timeout eviction, optional online-mode username validation.
  Requires Linux kernel ≥ 5.15, IPv4, MC 1.8–latest, root.
- **Bedrock Edition:** `Upioti/minecraft-bedrock-xdp-ebpf` — Go userspace + C
  eBPF, validates RakNet magic/packet-IDs, per-IP pps throttling, amplification
  source-port filtering. Kernel ≥ 5.10.

**Australis's job around them:** package, configure, auto-install, and — most
importantly — **wire the plugin's feedback loop into their blocklist maps** so
detection and enforcement become one system instead of two disconnected tools.
(License-permitting; otherwise we ship our own thin eBPF filter with the same
techniques. See `edge/xdp/README.md`.)

---

## 5. Handling the one hard case (volumetric) — for free

If an attacker throws more raw bandwidth than your uplink can hold, no on-box
filter helps: the congestion is *upstream* of your NIC. You cannot make
bandwidth for free — but you can **stand behind someone who already gives it
away**, because their business model subsidizes it:

| Free upstream | What it gives you | Trade-off |
|---|---|---|
| **playit.gg (free)** | Global **anycast** network — attack traffic is spread across many datacenters instead of one IP; hides your home IP; zero infra to run | Routing not always optimal; you depend on them; branding on the address |
| **TCPShield (free tier)** | Rides their 16 Tbps L4 / high-CPS L7 network; purpose-built for MC; hides IP | Free tier is capped/limited; it's a competitor product (fine to layer under, ironic to depend on) |
| **Oracle Cloud always-free VM** | A real box *you* control with **10 TB/month egress free** and unlimited-ish bandwidth on a large provider backbone with baseline network protection — perfect host for **Layer 1 (XDP)** as *your own* edge | ARM capacity can be hard to grab; you operate it; baseline (not Tbps) protection |

**Design decision:** Australis supports **both** models and lets the operator pick:

- **Zero-ops mode:** point players at a **playit.gg** (or TCPShield-free)
  address → origin hidden, volumetric absorbed by their anycast, and Australis's
  plugin still does all L7 verification on the backend. Nothing to host.
- **Self-edge mode:** spin up a **free Oracle VM**, run the **XDP filter +
  a thin TCP forwarder** on it → you own the edge, get line-rate L3/L4 + L7
  packet filtering *and* IP hiding, still $0.

Either way the plugin (Layer 2) is identical. That's the whole point: one
product, two deployment shapes, both free.

---

## 6. What we can and cannot promise (put this in the README verbatim)

**Australis protects against:**
- ✅ Bot join floods (L7) — verification stops them cold
- ✅ Status/ping floods (L7)
- ✅ SYN floods & malformed-packet floods up to your line rate (L3/L4 via XDP)
- ✅ Protocol-exploit / crash packets (bad VarInts, oversized payloads)
- ✅ Origin-IP hiding (via edge/tunnel)
- ✅ Amplification/reflection junk on the game port (XDP source-port drop)

**Australis does NOT (and no free tool can) guarantee:**
- ❌ Absorbing a raw volumetric flood **larger than your uplink** *when you run
  your own single edge box*. Mitigation: use anycast upstream (playit) or a
  paid Tbps scrubber for that tier. We tell users this plainly.

Being upfront here is a feature: it builds trust and keeps you out of "your
thing didn't save me from a 300 Gbps flood" drama.

---

## 7. Why this is genuinely $0 for you (the maintainer)

- All compute runs on the **user's** hardware (their proxy + their free edge VM).
- Distribution via GitHub / Modrinth / Hangar — free.
- No central servers, no bandwidth bill, no per-user cost.
- Optional future "we host the edge for you" tier is the *only* thing that would
  ever cost money (bandwidth) — keep it opt-in/donation-funded so the core stays
  free forever.

---

## 8. Component summary

| Component | Path | Language | Status |
|---|---|---|---|
| Velocity plugin (Layer 2) | `plugin/` | Java | scaffolded in this repo (Phase 1) |
| XDP filter integration (Layer 1) | `edge/xdp/` | C/Rust or Go (vendored) | documented; integrate open-source filter |
| Edge TCP forwarder (Layer 0 self-mode) | `edge/proxy/` | Go (planned) | documented |
| Feedback bridge (plugin ↔ XDP map) | `plugin/.../edge/` | Java + local socket/CLI | designed (Phase 3) |

See `ROADMAP.md` for the build order and `DEPLOYMENT.md` for how an operator
actually stands it up on free infrastructure.
