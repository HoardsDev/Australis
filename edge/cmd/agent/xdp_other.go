//go:build !linux

package main

import (
	"errors"
	"net"
)

// xdpSink is a no-op on non-Linux platforms (the XDP map only exists on Linux).
// Keeps the agent cross-compilable; -xdp-map is refused at startup off Linux.
type xdpSink struct{}

func openXDPBlocklist(path string) (*xdpSink, error) {
	return nil, errors.New("the XDP blocklist requires Linux")
}

func (x *xdpSink) block(ip net.IP, banSeconds int) error {
	return errors.New("xdp unsupported on this platform")
}

func (x *xdpSink) close() {}
