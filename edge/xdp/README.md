# Australis Edge — Layer 1: XDP/eBPF kernel filter

**L3/L4 packet filtering at line rate**, in the NIC driver, before the kernel
spends memory on a packet. This is Australis's own eBPF filter (no vendored
third-party loader) — a small, auditable program plus a Go loader that fits the
rest of the Go edge.

> Status: **built, tested, and live-validated** (707k SYNs dropped in 4 s on a
> test box; agent-driven bans dropped at the NIC). Opt-in because it needs a
> NIC/kernel that supports XDP.

## What it does (`australis_xdp.c`)
IPv4/TCP, on the game port:
- **Per-source SYN-flood drop** — a per-IP window (`-syn-per-window` / `-window`);
  SYNs over the limit are `XDP_DROP`ped at the driver.
- **Convicted-IP blocklist** — an expiring hash map (`australis_blocklist`) the
  `australis-agent` writes when the plugin convicts an IP. Matching packets are
  dropped at the NIC. This is the feedback loop, moved from nftables to XDP.
- Drops truncated TCP as malformed; passes everything else. Counters in
  `australis_stats`.

Not yet in the program (nftables/L2 cover these meanwhile): deep MC-protocol /
VarInt validation, amplification source-port drops, IPv6, Bedrock/RakNet.

## Components
- `australis_xdp.c` — the eBPF program (compiled to `australis_bpf{el,eb}.o`).
- `australis_bpf*.go` / `.o` — bpf2go output, **committed** so builds need no clang.
- `loader.go` + `../cmd/xdp-loader` — cilium/ebpf loader: attaches the program,
  sets config, pins the blocklist map at `/sys/fs/bpf/australis_blocklist`.
- The agent's `-xdp-map` flag writes bans into that pinned map.

## Enable it
```bash
sudo XDP=1 ORIGIN=<origin-ip>:25565 ./install-edge.sh
# or manually:
sudo /opt/australis/xdp-loader -iface eth0 -port 25565 -syn-per-window 40 -window 3s
```
Requires: Linux kernel ≥ 5.15, root (CAP_BPF/CAP_NET_ADMIN), a driver with XDP
support (virtio, ixgbe, i40e, mlx5, …). Verify: `sudo bpftool prog show | grep australis`.

## Rebuild the BPF object (only when editing the `.c`)
Needs `clang`, `libbpf-dev`, and a recent Go, on Linux:
```bash
cd edge && go generate ./xdp   # runs bpf2go; regenerates australis_bpf*.{go,o}
```
The `//go:generate` directive is in `loader.go` (adjust the `-I` multiarch include
if regenerating on non-amd64).

## Reality check
- XDP defeats **packet/pps floods and protocol junk up to your line rate** — a
  massive CPU saver, the reason a small VM shrugs off attacks that bury a naive box.
- XDP does **not** create bandwidth. A flood larger than the edge's uplink is a
  Layer-0 problem (anycast upstream / paid scrubber). See `../../docs/ARCHITECTURE.md` §5.
