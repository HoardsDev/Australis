# Changelog

All notable changes to Australis. Format loosely follows
[Keep a Changelog](https://keepachangelog.com/); this project uses SemVer.

## [Unreleased]

### Added
- **Prometheus metrics** on the Velocity & Paper plugins (opt-in `metrics.enabled`
  + `metrics.bind`): `/metrics` (australis_* counters — attacks, blocks,
  verifications, edge pushes) and `/health`, for Grafana dashboards. Shared
  `MetricsServer`/`PrometheusExporter` in `common/` (JDK-only), with a live
  HTTP round-trip test.
- **`testkit/lab.sh`** — self-attack regression lab: runs a matrix of floodtest
  scenarios against a target you own and writes a summary table, for
  baseline-vs-protected comparison after changes.
- **`limbo/` — the limbo verifier plugin**, completing deep verification: a Paper
  plugin that holds routed clients, checks for real-client behaviour, and signals
  `australis:verify`; the proxy then promotes the IP and moves them to a real
  server. Wired into CI + the `builds` branch. Setup in `DEPLOYMENT.md`.
- Handshake-stage attack detection + per-IP connection limiting on Velocity
  (`ConnectionHandshakeEvent`), with the kick enforced at `PreLogin`. Detection
  now reflects true volume instead of the post-`login-ratelimit` trickle.
- Forwarder: global + per-source-IP connection caps, idle/slowloris timeouts,
  half-close piping, graceful drain on SIGTERM, and an optional plaintext
  `/metrics` endpoint. All flags take env-var fallbacks for systemd.
- `ConfigSanitizer` — config values are validated/clamped with warnings.
- Deep-verification (limbo) setup guide in `docs/DEPLOYMENT.md`.
- `docs/FINDINGS-live-test.md` — honest live flood-test writeup.
- `.gitattributes` enforcing LF so a Windows checkout can't ship CRLF that breaks
  Linux `nft`/shell/systemd.

### Changed
- Documentation now clearly separates what ships today (L2 plugin + L0 forwarder +
  nftables) from what is planned (XDP/eBPF line-rate filter). Capability matrices
  no longer mark XDP-dependent protection as done.
- Fixed the Velocity config key in all docs: `haproxy-protocol` (not
  `proxy-protocol`) — the wrong key silently broke edge IP forwarding.
- nftables SYN rate-limit is now **per-source** and scoped to the game port, and
  accepts established/related + loopback first (no SSH/legit-join lockout).

### Security (from the pre-publish audit)
- Agent reads the bearer token from the `TOKEN` env var, never argv — it can no
  longer be read via `/proc/<pid>/cmdline` or `ps`. Constant-time comparison.
- `install-edge.sh` chmods `forwarder.env`/`agent.env`/`xdp.env` to 600 and
  `/etc/australis` to 700 (the hidden origin address was world-readable).
- `EdgeClient` warns on plaintext HTTP to a non-private host, and uses proper
  JSON escaping.
- Velocity config YAML is loaded via `SafeConstructor`.

### Fixed
- Limbo routing was dead code: the proxy auto-verified every IP at post-login,
  before the initial-server choice, so unverified players were never routed to
  limbo. Auto-verify now happens only on reaching a *real* backend.
- `EdgeClient` now uses a bounded queue + discard policy (was an unbounded queue —
  a heap-exhaustion DoS on the defender under a unique-IP flood).
- Zero/negative config values no longer crash startup or pin permanent attack mode.
- Attack-state transition logging is race-free (`AtomicBoolean` CAS).
- Paper plugin brought to parity with Velocity (attack logging, block-log gating,
  null-address guards).

## [0.2.0]
- Initial scaffold: Velocity + Paper plugins sharing `gg.australis.core`, Go edge
  (TCP forwarder with PROXY v2 + nftables feedback agent), nftables ruleset,
  systemd units, `install-edge.sh`, GitHub Actions CI, and the design docs.
