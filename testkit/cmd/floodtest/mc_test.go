package main

import (
	"bufio"
	"bytes"
	"encoding/binary"
	"testing"
)

func TestWriteVarInt(t *testing.T) {
	cases := []struct {
		in   int
		want []byte
	}{
		{0, []byte{0x00}},
		{1, []byte{0x01}},
		{127, []byte{0x7f}},
		{128, []byte{0x80, 0x01}},
		{255, []byte{0xff, 0x01}},
		{25565, []byte{0xdd, 0xc7, 0x01}},
		{2097151, []byte{0xff, 0xff, 0x7f}},
		{2147483647, []byte{0xff, 0xff, 0xff, 0xff, 0x07}},
	}
	for _, c := range cases {
		got := writeVarInt(nil, c.in)
		if !bytes.Equal(got, c.want) {
			t.Errorf("writeVarInt(%d) = % x, want % x", c.in, got, c.want)
		}
	}
}

func TestVarIntRoundTrip(t *testing.T) {
	for _, v := range []int{0, 1, 127, 128, 300, 25565, 1_000_000, 2147483647} {
		enc := writeVarInt(nil, v)
		got, err := readVarInt(bytes.NewReader(enc))
		if err != nil {
			t.Fatalf("readVarInt(% x): %v", enc, err)
		}
		if got != v {
			t.Errorf("round trip %d -> %d", v, got)
		}
	}
}

func TestWriteString(t *testing.T) {
	got := writeString(nil, "abc")
	want := []byte{0x03, 'a', 'b', 'c'}
	if !bytes.Equal(got, want) {
		t.Errorf("writeString = % x, want % x", got, want)
	}
}

func TestFrameAndReadPacket(t *testing.T) {
	body := []byte{0xde, 0xad, 0xbe, 0xef}
	f := frame(0x02, body)
	r := bufio.NewReader(bytes.NewReader(f))
	id, gotBody, err := readPacket(r)
	if err != nil {
		t.Fatalf("readPacket: %v", err)
	}
	if id != 0x02 {
		t.Errorf("id = %d, want 2", id)
	}
	if !bytes.Equal(gotBody, body) {
		t.Errorf("body = % x, want % x", gotBody, body)
	}
}

func TestBuildHandshakeParsesBack(t *testing.T) {
	pkt := buildHandshake(767, "mc.example.net", 25565, 2)
	r := bufio.NewReader(bytes.NewReader(pkt))
	id, body, err := readPacket(r)
	if err != nil {
		t.Fatalf("readPacket: %v", err)
	}
	if id != 0x00 {
		t.Fatalf("handshake id = %d, want 0", id)
	}
	br := newByteSlabReader(body)
	proto, _ := readVarInt(br)
	if proto != 767 {
		t.Errorf("protocol = %d, want 767", proto)
	}
	strLen, _ := readVarInt(br)
	host := string(body[br.pos : br.pos+strLen])
	br.pos += strLen
	if host != "mc.example.net" {
		t.Errorf("host = %q", host)
	}
	port := binary.BigEndian.Uint16(body[br.pos : br.pos+2])
	br.pos += 2
	if port != 25565 {
		t.Errorf("port = %d, want 25565", port)
	}
	next, _ := readVarInt(br)
	if next != 2 {
		t.Errorf("nextState = %d, want 2", next)
	}
}

func TestBuildLoginStartWithUUID(t *testing.T) {
	uuid := make([]byte, 16)
	pkt := buildLoginStart("bot_abc", uuid)
	r := bufio.NewReader(bytes.NewReader(pkt))
	id, body, err := readPacket(r)
	if err != nil {
		t.Fatalf("readPacket: %v", err)
	}
	if id != 0x00 {
		t.Fatalf("login id = %d, want 0", id)
	}
	br := newByteSlabReader(body)
	nameLen, _ := readVarInt(br)
	name := string(body[br.pos : br.pos+nameLen])
	if name != "bot_abc" {
		t.Errorf("name = %q", name)
	}
	// name + uuid(16) should account for the rest.
	if got := len(body) - br.pos - nameLen; got != 16 {
		t.Errorf("trailing uuid bytes = %d, want 16", got)
	}
}
