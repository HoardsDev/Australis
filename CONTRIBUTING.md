# Contributing to Australis

Thanks for helping build free protection for the Minecraft community.

## Repo layout
- `plugin/` — Velocity plugin (Java 17, Gradle). Layer 2 (L7).
- `edge/` — Go binaries (forwarder + feedback agent) + nftables + systemd. Layer 0/1.
- `docs/` — architecture, threat model, deployment, roadmap.

## Building
```bash
# Plugin
cd plugin && ./gradlew shadowJar     # -> build/libs/*.jar   (needs JDK 17+)

# Edge
cd edge && go vet ./... && go build ./cmd/...
```

## Guidelines
- **Honesty about protection.** Every defence must map to a real attack in
  `docs/THREAT-MODEL.md`. Don't claim to stop what we can't — especially don't
  imply we absorb arbitrary volumetric floods on a single self-hosted edge.
- **Cheap-first.** The hot path runs during floods. Prefer lock-free, allocation-light
  code; reject bad traffic as early and as cheaply as possible.
- **Attack-gated aggression.** Anything that could inconvenience real players
  (challenges, strict limits) should only trigger under an actual attack.
- **Test what you can.** Go code should `go vet` clean. Only test attacks against
  infrastructure you own.

## Prior art to learn from
- Sonar (antibot), Outfluencer/Minecraft-XDP-eBPF, Upioti/minecraft-bedrock-xdp-ebpf.
  Check licenses before vendoring any of it.
