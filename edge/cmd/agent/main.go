// Command agent is the Australis edge feedback bridge (Layer 1 enforcement).
//
// It exposes a tiny authenticated HTTP API on the edge box. When the Velocity
// plugin confirms an IP is malicious, it POSTs it here and the agent drops that
// IP at the kernel by adding it to an nftables set (with a timeout). From then
// on the attacker is dropped in the network path, costing the box almost
// nothing — this is the feedback loop that turns L7 detection into cheap L3/L4
// enforcement. (The nftables set is a pragmatic enforcement target that works
// today; the XDP blocklist map is the higher-performance upgrade — see
// edge/xdp/README.md.)
//
// The set is expected to exist (created by install-edge.sh / australis.nft):
//
//	table inet australis { set blocklist { type ipv4_addr; flags timeout; } }
//
// Standard library only.
package main

import (
	"crypto/subtle"
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"os/exec"
	"strings"
	"time"
)

type blockRequest struct {
	IP         string `json:"ip"`
	BanSeconds int    `json:"ban_seconds"`
	Reason     string `json:"reason"`
}

type server struct {
	token     string
	table     string
	set4      string
	set6      string
	maxBanSec int
	useNft    bool     // enforce via nftables (also the fallback when no XDP map)
	xdp       *xdpSink // enforce at the NIC via the XDP blocklist map (optional)
	// nft runs an nftables command; injectable so tests don't need real nft.
	nft func(args ...string) error
}

func main() {
	listen := flag.String("listen", "127.0.0.1:8787", "address to listen on (bind to your private link, not 0.0.0.0)")
	// Prefer the TOKEN env var so the secret never appears in the process's
	// command line (world-readable via /proc/<pid>/cmdline and `ps`). The -token
	// flag remains for manual/testing use but is discouraged for that reason.
	token := flag.String("token", os.Getenv("TOKEN"), "shared bearer token (required; prefer the TOKEN env var over this flag)")
	table := flag.String("table", "inet australis", "nftables table (family + name)")
	set4 := flag.String("set4", "blocklist", "nftables ipv4 set name")
	set6 := flag.String("set6", "blocklist6", "nftables ipv6 set name")
	maxBan := flag.Int("max-ban-seconds", 86400, "clamp requested ban duration to this maximum")
	xdpMap := flag.String("xdp-map", "", "path to the pinned XDP blocklist map for NIC-level drop (e.g. /sys/fs/bpf/australis_blocklist)")
	useNft := flag.Bool("nft", true, "enforce via the nftables set (set -nft=false on an XDP-only edge)")
	flag.Parse()

	if *token == "" {
		log.Fatal("australis-agent: -token is required")
	}

	var xsink *xdpSink
	if *xdpMap != "" {
		x, err := openXDPBlocklist(*xdpMap)
		if err != nil {
			log.Fatalf("australis-agent: %v", err)
		}
		xsink = x
		log.Printf("australis-agent: XDP enforcement via %s", *xdpMap)
	}
	if !*useNft && xsink == nil {
		log.Fatal("australis-agent: no enforcement backend — enable -nft or set -xdp-map")
	}

	s := &server{token: *token, table: *table, set4: *set4, set6: *set6, maxBanSec: *maxBan, useNft: *useNft, xdp: xsink, nft: realNft}

	mux := http.NewServeMux()
	mux.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		fmt.Fprintln(w, "ok")
	})
	mux.HandleFunc("/block", s.handleBlock)

	srv := &http.Server{
		Addr:         *listen,
		Handler:      mux,
		ReadTimeout:  5 * time.Second,
		WriteTimeout: 5 * time.Second,
	}
	log.Printf("australis-agent: listening on %s (table %q, set4 %q, set6 %q)", *listen, *table, *set4, *set6)
	log.Fatal(srv.ListenAndServe())
}

func (s *server) handleBlock(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if !s.authorized(r) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}

	var req blockRequest
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 4096)).Decode(&req); err != nil {
		http.Error(w, "bad request", http.StatusBadRequest)
		return
	}

	// Validate the IP with the standard parser — this is the injection guard.
	ip := net.ParseIP(strings.TrimSpace(req.IP))
	if ip == nil {
		http.Error(w, "invalid ip", http.StatusBadRequest)
		return
	}
	ban := req.BanSeconds
	if ban <= 0 {
		ban = 600
	}
	if ban > s.maxBanSec {
		ban = s.maxBanSec
	}

	enforced := false
	if s.xdp != nil {
		if err := s.xdp.block(ip, ban); err != nil {
			log.Printf("australis-agent: xdp add failed for %s: %v", ip, err)
		} else {
			enforced = true
		}
	}
	// Use nftables when explicitly enabled, or as the fallback when no XDP map is
	// configured (also the path the unit tests exercise).
	if s.useNft || s.xdp == nil {
		set := s.set4
		if ip.To4() == nil {
			set = s.set6
		}
		if err := s.addToSet(set, ip.String(), ban); err != nil {
			log.Printf("australis-agent: nft add failed for %s: %v", ip, err)
		} else {
			enforced = true
		}
	}
	if !enforced {
		http.Error(w, "enforcement failed", http.StatusInternalServerError)
		return
	}
	log.Printf("australis-agent: blocked %s for %ds (%s)", ip, ban, sanitize(req.Reason))
	w.WriteHeader(http.StatusNoContent)
}

func (s *server) authorized(r *http.Request) bool {
	auth := r.Header.Get("Authorization")
	want := "Bearer " + s.token
	// Constant-time compare (returns 0 on length mismatch) to avoid leaking the
	// token via response timing.
	return subtle.ConstantTimeCompare([]byte(auth), []byte(want)) == 1
}

func (s *server) addToSet(set, ip string, banSeconds int) error {
	// Table is "family name" (e.g. "inet australis"); ip is already validated by
	// net.ParseIP, so it cannot contain shell/nft metacharacters. We invoke nft
	// directly (no shell), so there is no injection surface.
	parts := strings.Fields(s.table)
	element := fmt.Sprintf("{ %s timeout %ds }", ip, banSeconds)
	args := append([]string{"add", "element"}, parts...)
	args = append(args, set, element)
	return s.nft(args...)
}

// realNft invokes the nftables binary directly (no shell).
func realNft(args ...string) error {
	cmd := exec.Command("nft", args...)
	out, err := cmd.CombinedOutput()
	if err != nil {
		return fmt.Errorf("%v: %s", err, strings.TrimSpace(string(out)))
	}
	return nil
}

func sanitize(s string) string {
	s = strings.ReplaceAll(s, "\n", " ")
	if len(s) > 64 {
		s = s[:64]
	}
	return s
}
