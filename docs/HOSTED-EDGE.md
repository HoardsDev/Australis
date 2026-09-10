# Australis — Hosted Edge (optional, the only paid tier)

Everything else in Australis is free because it runs on the user's own hardware.
The **one** thing that genuinely costs money is *bandwidth to absorb large
volumetric floods* — and that cost is unavoidable, because you cannot
manufacture a big pipe for free. This document designs an **optional** hosted
edge for the minority of servers that face attacks bigger than a single
self-hosted box can hold, while keeping the core project free forever.

## Principle
The free product must never regress. The hosted edge is a convenience/scale
add-on, not a paywall in front of protection people already have. If it never
gets built, the free stack still fully protects the vast majority of servers.

## What it is
A managed version of the Layer 0 edge: instead of the user running one Oracle
VM, Australis (you) runs **several edge nodes across regions** behind one
address, so attack traffic is spread and the origin is hidden — the same shape
as playit.gg / TCPShield, but Australis-native and integrated with the plugin's
feedback loop.

```
Players ─► anycast / GeoDNS ─► [ edge node A ] ┐
                              [ edge node B ] ├─► (XDP + forwarder + agent) ─► origin
                              [ edge node C ] ┘        every node runs the same
                                                       Australis edge stack
```

## How to distribute the flood (cheapest → priciest)
1. **GeoDNS / multiple A records** across cheap DDoS-protected hosts (OVH,
   Hetzner, Path.net boxes). Simplest; a single resolved IP can still be
   targeted, but attackers usually hit the hostname.
2. **BGP anycast** — one IP announced from many sites, so the flood is split by
   the internet's own routing. Requires your **own ASN + IP block + transit**
   (real money and paperwork). This is the "proper" version.
3. **Ride a scrubbing provider** (Path.net, Cosmic Guard, OVH VAC) as upstream
   and resell — least infra, thinnest margins.

## Cost drivers (be honest in pricing)
- **Transit/bandwidth during attacks** — the dominant cost; scrubbing providers
  bill for it.
- **Per-node servers** across regions.
- **ASN + IP space** (one-time + annual) if you do real anycast.
- **On-call time** — novel L7 attacks need a human sometimes.

## Funding models that keep the core free
- **Donations / sponsors** (OpenCollective, GitHub Sponsors) cover a small
  shared free anycast pool.
- **Paid tier** only for hosted edge + higher capacity + analytics; self-hosted
  stays 100% free.
- **BYO-upstream**: paid users bring their own OVH/Path.net box; Australis just
  manages it. Shifts bandwidth cost to them, you charge for software/management.

## Integration with the rest of Australis
- Hosted edge nodes run the **same** `forwarder` + `agent` + XDP as self-mode,
  so there's one codebase.
- The plugin's feedback loop points at the managed agent endpoint instead of a
  self-hosted one — no plugin changes needed.
- Signup/billing could plug into the existing **the store store** front-end
  (Next.js) if you want one dashboard.

## Recommendation
Do **not** build this until the free stack has real adoption and you see servers
actually hitting the volumetric ceiling. When you do, start at option 1 (GeoDNS
over a couple of cheap protected boxes) funded by donations, and only pursue
anycast (option 2) if demand and funding justify it.
