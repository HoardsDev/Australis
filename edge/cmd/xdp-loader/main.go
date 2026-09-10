//go:build linux

// Command xdp-loader attaches the Australis XDP/eBPF filter to a NIC and keeps
// it attached (XDP detaches when the loader exits). It pins the convicted-IP
// blocklist map so the australis-agent can drop IPs at the NIC. Linux only.
//
//	sudo xdp-loader -iface eth0 -port 25565 -syn-per-window 40 -window 3s
package main

import (
	"flag"
	"log"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/negativevibez/australis/edge/xdp"
)

func main() {
	iface := flag.String("iface", "", "network interface to attach to (required)")
	port := flag.Int("port", 25565, "game port to SYN-rate-limit")
	synPerWindow := flag.Int("syn-per-window", 40, "max new SYNs per window per source IP")
	window := flag.Duration("window", 3*time.Second, "SYN rate-limit window")
	statsInterval := flag.Duration("stats-interval", 0, "log drop counters every N (0 = off)")
	flag.Parse()

	if *iface == "" {
		log.Fatal("australis-xdp: -iface is required")
	}

	l, err := xdp.Load(xdp.Options{
		Iface:        *iface,
		GamePort:     uint16(*port),
		SynPerWindow: uint32(*synPerWindow),
		Window:       *window,
	})
	if err != nil {
		log.Fatalf("australis-xdp: %v", err)
	}
	defer l.Close()

	log.Printf("australis-xdp: attached to %s (game port %d, SYN limit %d/%s); blocklist pinned at %s",
		*iface, *port, *synPerWindow, *window, xdp.PinnedBlocklistPath)

	sig := make(chan os.Signal, 1)
	signal.Notify(sig, os.Interrupt, syscall.SIGTERM)

	var ticker <-chan time.Time
	if *statsInterval > 0 {
		t := time.NewTicker(*statsInterval)
		defer t.Stop()
		ticker = t.C
	}

	for {
		select {
		case <-sig:
			s := l.Stats()
			log.Printf("australis-xdp: detaching (passed=%d blocklist=%d synflood=%d malformed=%d)",
				s["passed"], s["dropped_blocklist"], s["dropped_synflood"], s["dropped_malformed"])
			return
		case <-ticker:
			s := l.Stats()
			log.Printf("australis-xdp: passed=%d blocklist=%d synflood=%d malformed=%d",
				s["passed"], s["dropped_blocklist"], s["dropped_synflood"], s["dropped_malformed"])
		}
	}
}
