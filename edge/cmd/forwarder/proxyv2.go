package main

import (
	"encoding/binary"
	"errors"
	"net"
)

// PROXY protocol v2 signature (12 bytes).
var proxyV2Sig = []byte{
	0x0D, 0x0A, 0x0D, 0x0A, 0x00, 0x0D,
	0x0A, 0x51, 0x55, 0x49, 0x54, 0x0A,
}

// buildProxyV2Header constructs a PROXY protocol v2 header describing the real
// client (src) and the address it connected to (dst). This lets the origin
// (Velocity with proxy-protocol enabled) recover the true client IP.
//
// See: https://www.haproxy.org/download/2.8/doc/proxy-protocol.txt
func buildProxyV2Header(src, dst net.Addr) ([]byte, error) {
	srcTCP, ok1 := src.(*net.TCPAddr)
	dstTCP, ok2 := dst.(*net.TCPAddr)
	if !ok1 || !ok2 {
		return nil, errors.New("non-TCP address")
	}

	srcIP4 := srcTCP.IP.To4()
	dstIP4 := dstTCP.IP.To4()
	isIPv4 := srcIP4 != nil && dstIP4 != nil

	header := make([]byte, 0, 52)
	header = append(header, proxyV2Sig...)

	// Version (2) + command (1 = PROXY). 0x20 | 0x01 = 0x21.
	header = append(header, 0x21)

	var addrBlock []byte
	if isIPv4 {
		// family AF_INET (0x10) | proto STREAM (0x01) = 0x11
		header = append(header, 0x11)
		addrBlock = make([]byte, 12)
		copy(addrBlock[0:4], srcIP4)
		copy(addrBlock[4:8], dstIP4)
		binary.BigEndian.PutUint16(addrBlock[8:10], uint16(srcTCP.Port))
		binary.BigEndian.PutUint16(addrBlock[10:12], uint16(dstTCP.Port))
	} else {
		// family AF_INET6 (0x20) | proto STREAM (0x01) = 0x21
		header = append(header, 0x21)
		srcIP6 := srcTCP.IP.To16()
		dstIP6 := dstTCP.IP.To16()
		if srcIP6 == nil || dstIP6 == nil {
			return nil, errors.New("invalid IPv6 address")
		}
		addrBlock = make([]byte, 36)
		copy(addrBlock[0:16], srcIP6)
		copy(addrBlock[16:32], dstIP6)
		binary.BigEndian.PutUint16(addrBlock[32:34], uint16(srcTCP.Port))
		binary.BigEndian.PutUint16(addrBlock[34:36], uint16(dstTCP.Port))
	}

	lenBytes := make([]byte, 2)
	binary.BigEndian.PutUint16(lenBytes, uint16(len(addrBlock)))
	header = append(header, lenBytes...)
	header = append(header, addrBlock...)
	return header, nil
}
