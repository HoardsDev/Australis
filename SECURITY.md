# Security Policy

## Reporting a vulnerability
Please report security issues privately via GitHub Security Advisories on this
repository (Security → Report a vulnerability), not in public issues. Include
reproduction steps and impact. We'll acknowledge and work on a fix before public
disclosure.

## Scope
Australis is defensive software. In scope:
- Bypasses that let attack traffic reach a protected backend.
- The edge feedback agent (auth bypass, injection, unauthorized blocklisting).
- Resource-exhaustion in the plugin's own hot path (an attack that turns the
  mitigation into the bottleneck).

## The feedback agent
The `edge/cmd/agent` HTTP API can add IPs to a kernel blocklist, so:
- Bind it to a **private** interface (WireGuard/private link), never `0.0.0.0`.
- Use a strong, unique `token`. It's compared in constant time.
- IPs are validated with the standard parser and `nft` is invoked without a
  shell, so there is no command-injection surface — but review any change there
  carefully.

## Honest limits (not vulnerabilities)
- A volumetric flood larger than a single self-hosted edge's uplink is a
  documented limitation, not a bug — see `docs/THREAT-MODEL.md` #8. Use anycast
  upstream or paid scrubbing for that tier.

## Legal
Only test attacks against infrastructure you own or are authorized to test.
