package main

import (
	"bufio"
	"encoding/binary"
	"errors"
	"io"
)

// Minimal Minecraft (Java) protocol helpers for load testing.
// Packet framing (uncompressed handshake/status/login): VarInt length prefix,
// then VarInt packet id, then the packet body.

// writeVarInt appends a Minecraft VarInt to b.
func writeVarInt(b []byte, v int) []byte {
	uv := uint32(v)
	for {
		if uv&^0x7F == 0 {
			return append(b, byte(uv))
		}
		b = append(b, byte(uv&0x7F)|0x80)
		uv >>= 7
	}
}

// writeString appends a length-prefixed UTF-8 string.
func writeString(b []byte, s string) []byte {
	b = writeVarInt(b, len(s))
	return append(b, s...)
}

// frame wraps a packet id + body with a VarInt length prefix.
func frame(packetID int, body []byte) []byte {
	var inner []byte
	inner = writeVarInt(inner, packetID)
	inner = append(inner, body...)
	var out []byte
	out = writeVarInt(out, len(inner))
	return append(out, inner...)
}

// buildHandshake builds the serverbound Handshake (0x00).
// nextState: 1 = status, 2 = login.
func buildHandshake(protocol int, host string, port uint16, nextState int) []byte {
	var body []byte
	body = writeVarInt(body, protocol)
	body = writeString(body, host)
	var p [2]byte
	binary.BigEndian.PutUint16(p[:], port)
	body = append(body, p[:]...)
	body = writeVarInt(body, nextState)
	return frame(0x00, body)
}

// buildStatusRequest builds the serverbound Status Request (0x00, empty body).
func buildStatusRequest() []byte {
	return frame(0x00, nil)
}

// buildLoginStart builds the serverbound Login Start (0x00): name, and for
// modern versions (1.20.2+) a 16-byte UUID. Passing a nil uuid omits it.
func buildLoginStart(name string, uuid []byte) []byte {
	var body []byte
	body = writeString(body, name)
	if len(uuid) == 16 {
		body = append(body, uuid...)
	}
	return frame(0x00, body)
}

// readVarInt reads a Minecraft VarInt from r.
func readVarInt(r io.ByteReader) (int, error) {
	var result uint32
	var shift uint
	for {
		bb, err := r.ReadByte()
		if err != nil {
			return 0, err
		}
		result |= uint32(bb&0x7F) << shift
		if bb&0x80 == 0 {
			break
		}
		shift += 7
		if shift >= 35 {
			return 0, errors.New("VarInt too long")
		}
	}
	return int(result), nil
}

// readPacket reads one framed packet, returning its id and body.
func readPacket(r *bufio.Reader) (int, []byte, error) {
	length, err := readVarInt(r)
	if err != nil {
		return 0, nil, err
	}
	if length <= 0 || length > 2<<20 {
		return 0, nil, errors.New("bad packet length")
	}
	buf := make([]byte, length)
	if _, err := io.ReadFull(r, buf); err != nil {
		return 0, nil, err
	}
	// Parse packet id VarInt from the front of buf.
	br := newByteSlabReader(buf)
	id, err := readVarInt(br)
	if err != nil {
		return 0, nil, err
	}
	return id, buf[br.pos:], nil
}

// byteSlabReader is a tiny io.ByteReader over a byte slice.
type byteSlabReader struct {
	b   []byte
	pos int
}

func newByteSlabReader(b []byte) *byteSlabReader { return &byteSlabReader{b: b} }

func (s *byteSlabReader) ReadByte() (byte, error) {
	if s.pos >= len(s.b) {
		return 0, io.EOF
	}
	c := s.b[s.pos]
	s.pos++
	return c, nil
}
