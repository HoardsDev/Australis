package main

import (
	"bytes"
	"encoding/binary"
	"net"
	"testing"
)

func TestBuildProxyV2HeaderIPv4(t *testing.T) {
	src := &net.TCPAddr{IP: net.ParseIP("203.0.113.7"), Port: 54321}
	dst := &net.TCPAddr{IP: net.ParseIP("198.51.100.9"), Port: 25565}

	h, err := buildProxyV2Header(src, dst)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	// 12 sig + 1 ver/cmd + 1 fam/proto + 2 len + 12 addr = 28 bytes.
	if len(h) != 28 {
		t.Fatalf("header length = %d, want 28", len(h))
	}
	if !bytes.Equal(h[:12], proxyV2Sig) {
		t.Errorf("bad signature: %x", h[:12])
	}
	if h[12] != 0x21 {
		t.Errorf("ver/cmd = %#x, want 0x21", h[12])
	}
	if h[13] != 0x11 {
		t.Errorf("fam/proto = %#x, want 0x11 (AF_INET+STREAM)", h[13])
	}
	if l := binary.BigEndian.Uint16(h[14:16]); l != 12 {
		t.Errorf("addr length = %d, want 12", l)
	}
	if got := net.IP(h[16:20]).String(); got != "203.0.113.7" {
		t.Errorf("src ip = %s, want 203.0.113.7", got)
	}
	if got := net.IP(h[20:24]).String(); got != "198.51.100.9" {
		t.Errorf("dst ip = %s, want 198.51.100.9", got)
	}
	if p := binary.BigEndian.Uint16(h[24:26]); p != 54321 {
		t.Errorf("src port = %d, want 54321", p)
	}
	if p := binary.BigEndian.Uint16(h[26:28]); p != 25565 {
		t.Errorf("dst port = %d, want 25565", p)
	}
}

func TestBuildProxyV2HeaderIPv6(t *testing.T) {
	src := &net.TCPAddr{IP: net.ParseIP("2001:db8::1"), Port: 40000}
	dst := &net.TCPAddr{IP: net.ParseIP("2001:db8::2"), Port: 25565}

	h, err := buildProxyV2Header(src, dst)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	// 16 header + 36 addr = 52 bytes.
	if len(h) != 52 {
		t.Fatalf("header length = %d, want 52", len(h))
	}
	if h[13] != 0x21 {
		t.Errorf("fam/proto = %#x, want 0x21 (AF_INET6+STREAM)", h[13])
	}
	if l := binary.BigEndian.Uint16(h[14:16]); l != 36 {
		t.Errorf("addr length = %d, want 36", l)
	}
	if got := net.IP(h[16:32]).String(); got != "2001:db8::1" {
		t.Errorf("src ip = %s, want 2001:db8::1", got)
	}
}

func TestBuildProxyV2HeaderRejectsNonTCP(t *testing.T) {
	if _, err := buildProxyV2Header(&net.UDPAddr{}, &net.TCPAddr{}); err == nil {
		t.Error("expected error for non-TCP address, got nil")
	}
}
