//go:build linux

package main

import (
	"encoding/binary"
	"fmt"
	"net"

	"github.com/cilium/ebpf"
	"golang.org/x/sys/unix"
)

// blockEntry mirrors `struct block_entry` in edge/xdp/australis_xdp.c.
type blockEntry struct {
	ExpiryNs uint64
}

// xdpSink writes bans into the XDP filter's pinned blocklist map, so convicted
// IPs are dropped at the NIC driver. IPv4 only (the XDP map is ipv4_addr).
type xdpSink struct {
	m *ebpf.Map
}

func openXDPBlocklist(path string) (*xdpSink, error) {
	m, err := ebpf.LoadPinnedMap(path, nil)
	if err != nil {
		return nil, fmt.Errorf("open pinned XDP map %s: %w", path, err)
	}
	return &xdpSink{m: m}, nil
}

// monotonicNs returns CLOCK_MONOTONIC ns — the same clock the XDP program reads
// via bpf_ktime_get_ns(), so expiry comparisons line up.
func monotonicNs() uint64 {
	var ts unix.Timespec
	if err := unix.ClockGettime(unix.CLOCK_MONOTONIC, &ts); err != nil {
		return 0
	}
	return uint64(ts.Sec)*1_000_000_000 + uint64(ts.Nsec)
}

func (x *xdpSink) block(ip net.IP, banSeconds int) error {
	v4 := ip.To4()
	if v4 == nil {
		return fmt.Errorf("XDP blocklist is IPv4-only, got %s", ip)
	}
	// The map key is __u32 saddr in the packet's (network) byte order. cilium/ebpf
	// encodes a Go uint32 in host byte order, so build the uint32 whose memory
	// layout equals the 4 address bytes: little-endian of v4 (verified against the
	// kernel's stored key).
	key := binary.LittleEndian.Uint32(v4)
	var expiry uint64
	if banSeconds > 0 {
		expiry = monotonicNs() + uint64(banSeconds)*1_000_000_000
	}
	e := blockEntry{ExpiryNs: expiry}
	return x.m.Update(key, &e, ebpf.UpdateAny)
}

func (x *xdpSink) close() {
	if x.m != nil {
		_ = x.m.Close()
	}
}
