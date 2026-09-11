package xdp

// Packet-level regression test for the XDP filter. Feeds crafted frames through
// the real (verifier-checked) eBPF program via BPF_PROG_TEST_RUN and asserts the
// drop/pass verdict — proving both that malformed/crafted TCP is dropped and,
// just as importantly, that legitimate player traffic is never false-dropped.
//
// Needs root (CAP_BPF/CAP_SYS_ADMIN) to load a program and run the test syscall,
// so it self-skips when run unprivileged (e.g. ordinary CI).

import (
	"encoding/binary"
	"os"
	"testing"

	"github.com/cilium/ebpf"
	"github.com/cilium/ebpf/rlimit"
)

const (
	xdpDROP = 1
	xdpPASS = 2
)

// frame builds an Ethernet+IPv4+TCP frame; tcpLen < 20 truncates the TCP header.
func frame(srcPort, dstPort uint16, flags byte, tcpLen int) []byte {
	eth := make([]byte, 14)
	eth[12], eth[13] = 0x08, 0x00 // IPv4
	ip := make([]byte, 20)
	ip[0] = 0x45 // v4, ihl=5
	ip[9] = 6    // TCP
	binary.BigEndian.PutUint32(ip[12:], 0x0a000001)
	binary.BigEndian.PutUint32(ip[16:], 0x0a000002)
	tcp := make([]byte, 20)
	binary.BigEndian.PutUint16(tcp[0:], srcPort)
	binary.BigEndian.PutUint16(tcp[2:], dstPort)
	tcp[12] = 0x50 // data offset 5
	tcp[13] = flags
	if tcpLen < 20 {
		tcp = tcp[:tcpLen]
	}
	return append(append(eth, ip...), tcp...)
}

func TestMalformedDrops(t *testing.T) {
	if os.Geteuid() != 0 {
		t.Skip("needs root for BPF_PROG_TEST_RUN")
	}
	if err := rlimit.RemoveMemlock(); err != nil {
		t.Fatalf("memlock: %v", err)
	}
	var objs australisObjects
	if err := loadAustralisObjects(&objs, nil); err != nil {
		t.Fatalf("load (verifier): %v", err)
	}
	defer objs.Close()

	// game_port=25565, high SYN limit so it never interferes with these cases.
	cfg := australisConfig{GamePort: 25565, SynPerWindow: 1_000_000, WindowNs: 1_000_000_000}
	if err := objs.AustralisConfig.Update(uint32(0), cfg, ebpf.UpdateAny); err != nil {
		t.Fatalf("cfg: %v", err)
	}

	const SYN, ACK, RST, FIN, PSH, URG = 0x02, 0x10, 0x04, 0x01, 0x08, 0x20
	cases := []struct {
		name string
		pkt  []byte
		want uint32
	}{
		{"normal SYN, ephemeral src", frame(33000, 25565, SYN, 20), xdpPASS},
		{"normal ACK (established)", frame(33000, 25565, ACK, 20), xdpPASS},
		{"NULL scan", frame(33000, 25565, 0, 20), xdpDROP},
		{"SYN+FIN", frame(33000, 25565, SYN|FIN, 20), xdpDROP},
		{"SYN+RST", frame(33000, 25565, SYN|RST, 20), xdpDROP},
		{"XMAS scan", frame(33000, 25565, FIN|PSH|URG, 20), xdpDROP},
		{"SYN from privileged src :53", frame(53, 25565, SYN, 20), xdpDROP},
		{"truncated TCP header", frame(33000, 25565, SYN, 10), xdpDROP},
		{"weird flags but NOT game port", frame(33000, 22, 0, 20), xdpPASS},
	}
	verdict := map[uint32]string{0: "ABORT", 1: "DROP", 2: "PASS", 3: "TX"}
	for _, c := range cases {
		ret, err := objs.AustralisFilter.Run(&ebpf.RunOptions{Data: c.pkt})
		if err != nil {
			t.Fatalf("%s: run: %v", c.name, err)
		}
		if ret != c.want {
			t.Errorf("FAIL %-32s got %s want %s", c.name, verdict[ret], verdict[c.want])
		} else {
			t.Logf("ok   %-32s %s", c.name, verdict[ret])
		}
	}
}
