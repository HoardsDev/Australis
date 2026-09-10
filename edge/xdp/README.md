# Australis Edge — Layer 1: XDP/eBPF kernel filter

This is where **L3/L4 + protocol-level packet filtering at line rate** happens.
It runs on the **edge box** (your free Oracle VM in Mode B) and drops malicious
packets *in the NIC driver* before the kernel spends memory on them.

## Don't reinvent — vendor the proven filters

Two open-source, Minecraft-aware XDP filters already implement the hard parts.
Australis's value is packaging them, tuning them, and wiring the plugin feedback
loop into their blocklist maps — not rewriting eBPF from scratch.

### Java Edition — `Outfluencer/Minecraft-XDP-eBPF`
- Rust userspace loader + C eBPF program.
- SYN rate-limiting (default 10 SYN / 3s / IP), in-kernel connection tracking,
  handshake/status/login inspection, drops invalid packets / bad VarInts /
  protocol violations, idle-timeout eviction, optional online-mode username check.
- Requires: Linux kernel ≥ 5.15, IPv4, root, runs on the game port range.
- The userspace loader must stay running to keep the filter attached → use the
  systemd unit below.

### Bedrock Edition — `Upioti/minecraft-bedrock-xdp-ebpf`
- Go userspace + C eBPF. Validates RakNet magic/packet IDs, per-IP pps throttle,
  amplification source-port filtering. Kernel ≥ 5.10.

> Check each project's license before vendoring/redistributing. If a license
> doesn't allow bundling, ship an installer that fetches + builds it on the
> user's edge box instead, or write a thin equivalent using the same techniques.

## Integration TODO (Phase 3)
- [ ] `install-edge.sh`: detect distro, install deps (clang/llvm, libbpf, rustup
      or go), clone + build the chosen filter, attach to the NIC, enable systemd.
- [ ] Expose the filter's **blocklist map** via a tiny local API/socket
      (shared-token auth) so the Australis plugin can add IPs → see
      `plugin/.../edge/` (Phase 3).
- [ ] Prometheus scrape config for drop counters (both filters export metrics).

## Example systemd unit (Java filter)
```ini
# /etc/systemd/system/australis-xdp.service
[Unit]
Description=Australis XDP/eBPF Minecraft filter
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
ExecStart=/opt/australis/xdp-loader --iface eth0 --port 25565 \
          --syn-rate 10 --syn-window 3 --max-conns 4096
Restart=always
RestartSec=2
AmbientCapabilities=CAP_NET_ADMIN CAP_BPF CAP_SYS_ADMIN
User=root

[Install]
WantedBy=multi-user.target
```

## Reality check
- XDP defeats **packet/pps floods and protocol junk up to your line rate** — a
  massive CPU saver, and the reason a small free VM can shrug off attacks that
  would bury a naive setup.
- XDP does **not** create bandwidth. A flood larger than the edge's uplink is a
  Layer-0 problem (anycast upstream / paid scrubber). See `../../docs/ARCHITECTURE.md` §5.
