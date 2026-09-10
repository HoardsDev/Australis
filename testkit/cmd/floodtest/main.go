// Command floodtest is an Australis load/attack test harness.
//
// It simulates the exact attack vectors Australis defends against, so you can
// measure effectiveness against a real proxy. Run it from YOUR OWN machine
// against a target YOU OWN OR ARE AUTHORIZED TO TEST. Generating this traffic
// against anyone else's server is illegal.
//
// Modes:
//   ping   status/MOTD ping flood (measures response + latency)
//   join   bot join flood: handshake+login start, classify the proxy's reaction
//   conn   raw TCP connect/close flood
//   recon  reconnect-challenge check: join once, and if rejected, reconnect and
//          see whether the second attempt gets through (validates verification)
//
// Example:
//   floodtest -target play.example.net:25565 -mode join -c 200 -rate 500 -duration 30s
package main

import (
	"bufio"
	"context"
	"crypto/rand"
	"flag"
	"fmt"
	"log"
	"net"
	"os"
	"sort"
	"sync"
	"sync/atomic"
	"time"
)

type counters struct {
	attempts atomic.Int64
	ok       atomic.Int64 // ping: got status; join: proxy engaged (accepted)
	rejected atomic.Int64 // join: kicked/disconnected (Australis working)
	reset    atomic.Int64 // connection reset / EOF before response
	errored  atomic.Int64 // dial/write errors
}

type config struct {
	target      string
	host        string
	port        uint16
	mode        string
	protocol    int
	concurrency int
	rate        int
	duration    time.Duration
	timeout     time.Duration
	withUUID    bool
	confirm     bool
}

func main() {
	cfg := parseFlags()

	if !cfg.confirm {
		fmt.Fprintln(os.Stderr, "REFUSING TO RUN without -i-own-this-target.")
		fmt.Fprintln(os.Stderr, "Only test infrastructure you own or are authorized to test. Attacking")
		fmt.Fprintln(os.Stderr, "others is a crime. Re-run with -i-own-this-target to confirm.")
		os.Exit(2)
	}

	log.Printf("floodtest: mode=%s target=%s c=%d rate=%d/s duration=%s protocol=%d",
		cfg.mode, cfg.target, cfg.concurrency, cfg.rate, cfg.duration, cfg.protocol)

	var c counters
	var lat latencyCollector

	ctx, cancel := context.WithTimeout(context.Background(), cfg.duration)
	defer cancel()

	// Optional global rate limiter.
	var ticker *time.Ticker
	var permits <-chan time.Time
	if cfg.rate > 0 {
		ticker = time.NewTicker(time.Second / time.Duration(cfg.rate))
		permits = ticker.C
		defer ticker.Stop()
	}

	// Live progress printer.
	done := make(chan struct{})
	go progress(ctx, &c, done)

	var wg sync.WaitGroup
	for i := 0; i < cfg.concurrency; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			ls := lat.newSink()
			for {
				select {
				case <-ctx.Done():
					lat.commit(ls)
					return
				default:
				}
				if permits != nil {
					select {
					case <-ctx.Done():
						lat.commit(ls)
						return
					case <-permits:
					}
				}
				runOne(cfg, &c, ls)
			}
		}()
	}
	wg.Wait()
	close(done)

	report(cfg, &c, lat.merged())
}

func parseFlags() config {
	var cfg config
	flag.StringVar(&cfg.target, "target", "", "target host:port (required)")
	flag.StringVar(&cfg.mode, "mode", "ping", "ping | join | conn | recon")
	flag.IntVar(&cfg.protocol, "protocol", 767, "Minecraft protocol version number")
	flag.IntVar(&cfg.concurrency, "c", 50, "concurrent workers")
	flag.IntVar(&cfg.rate, "rate", 0, "global attempts/sec (0 = unthrottled)")
	flag.DurationVar(&cfg.duration, "duration", 15*time.Second, "test duration")
	flag.DurationVar(&cfg.timeout, "timeout", 3*time.Second, "per-connection timeout")
	flag.BoolVar(&cfg.withUUID, "uuid", true, "send a UUID in login start (1.20.2+ format)")
	flag.BoolVar(&cfg.confirm, "i-own-this-target", false, "confirm you own/are authorized to test the target")
	flag.Parse()

	if cfg.target == "" {
		fmt.Fprintln(os.Stderr, "-target host:port is required")
		os.Exit(2)
	}
	host, portStr, err := net.SplitHostPort(cfg.target)
	if err != nil {
		fmt.Fprintf(os.Stderr, "bad -target: %v\n", err)
		os.Exit(2)
	}
	var port int
	fmt.Sscanf(portStr, "%d", &port)
	if port <= 0 || port > 65535 {
		fmt.Fprintln(os.Stderr, "bad port in -target")
		os.Exit(2)
	}
	cfg.host = host
	cfg.port = uint16(port)
	return cfg
}

func runOne(cfg config, c *counters, ls *latencySink) {
	c.attempts.Add(1)
	switch cfg.mode {
	case "conn":
		doConn(cfg, c)
	case "ping":
		doPing(cfg, c, ls)
	case "join":
		doJoin(cfg, c, ls)
	case "recon":
		doRecon(cfg, c, ls)
	default:
		c.errored.Add(1)
	}
}

func dial(cfg config) (net.Conn, error) {
	d := net.Dialer{Timeout: cfg.timeout}
	return d.Dial("tcp", cfg.target)
}

func doConn(cfg config, c *counters) {
	conn, err := dial(cfg)
	if err != nil {
		c.errored.Add(1)
		return
	}
	conn.Close()
	c.ok.Add(1)
}

func doPing(cfg config, c *counters, ls *latencySink) {
	conn, err := dial(cfg)
	if err != nil {
		c.errored.Add(1)
		return
	}
	defer conn.Close()
	conn.SetDeadline(time.Now().Add(cfg.timeout))

	start := time.Now()
	if _, err := conn.Write(buildHandshake(cfg.protocol, cfg.host, cfg.port, 1)); err != nil {
		c.reset.Add(1)
		return
	}
	if _, err := conn.Write(buildStatusRequest()); err != nil {
		c.reset.Add(1)
		return
	}
	r := bufio.NewReader(conn)
	id, _, err := readPacket(r)
	if err != nil {
		c.reset.Add(1)
		return
	}
	if id == 0x00 {
		ls.add(time.Since(start))
		c.ok.Add(1)
	} else {
		c.reset.Add(1)
	}
}

func doJoin(cfg config, c *counters, ls *latencySink) {
	classifyJoin(cfg, c, ls)
}

// classifyJoin performs one join attempt and records the outcome.
// Returns true if the proxy engaged us (accepted into login), false if rejected.
func classifyJoin(cfg config, c *counters, ls *latencySink) bool {
	conn, err := dial(cfg)
	if err != nil {
		c.errored.Add(1)
		return false
	}
	defer conn.Close()
	conn.SetDeadline(time.Now().Add(cfg.timeout))

	start := time.Now()
	if _, err := conn.Write(buildHandshake(cfg.protocol, cfg.host, cfg.port, 2)); err != nil {
		c.reset.Add(1)
		return false
	}
	var uuid []byte
	if cfg.withUUID {
		uuid = make([]byte, 16)
		rand.Read(uuid)
	}
	if _, err := conn.Write(buildLoginStart(randomName(), uuid)); err != nil {
		c.reset.Add(1)
		return false
	}

	r := bufio.NewReader(conn)
	id, _, err := readPacket(r)
	ls.add(time.Since(start))
	if err != nil {
		// No response before timeout/close. A clean timeout means the proxy
		// accepted us into login and is waiting; a reset means it dropped us.
		if ne, ok := err.(net.Error); ok && ne.Timeout() {
			c.ok.Add(1) // engaged/accepted
			return true
		}
		c.reset.Add(1)
		return false
	}
	if id == 0x00 {
		// Login Disconnect — kicked (Australis challenge, auth, or rate limit).
		c.rejected.Add(1)
		return false
	}
	// 0x01 encryption request, 0x03 set compression, 0x02 success — engaged.
	c.ok.Add(1)
	return true
}

// doRecon simulates a real client: if the first join is rejected (reconnect
// challenge), it reconnects once and reports whether it then gets through.
func doRecon(cfg config, c *counters, ls *latencySink) {
	if classifyJoin(cfg, c, ls) {
		return // got in on first try
	}
	time.Sleep(500 * time.Millisecond) // human-like reconnect delay
	// second attempt counts as its own attempt
	c.attempts.Add(1)
	classifyJoin(cfg, c, ls)
}

func report(cfg config, c *counters, latencies []time.Duration) {
	secs := cfg.duration.Seconds()
	att := c.attempts.Load()
	fmt.Println()
	fmt.Println("==================== floodtest report ====================")
	fmt.Printf("mode=%s target=%s duration=%s concurrency=%d rate=%d/s\n",
		cfg.mode, cfg.target, cfg.duration, cfg.concurrency, cfg.rate)
	fmt.Printf("attempts:  %d  (%.0f/s)\n", att, float64(att)/secs)
	fmt.Printf("engaged:   %d  (got through / accepted into login or status)\n", c.ok.Load())
	fmt.Printf("rejected:  %d  (kicked by the proxy — protection acting)\n", c.rejected.Load())
	fmt.Printf("reset:     %d  (connection dropped before response)\n", c.reset.Load())
	fmt.Printf("errors:    %d  (dial/write failures)\n", c.errored.Load())
	if len(latencies) > 0 {
		sort.Slice(latencies, func(i, j int) bool { return latencies[i] < latencies[j] })
		fmt.Printf("latency:   p50=%s p95=%s p99=%s max=%s (n=%d)\n",
			pct(latencies, 50), pct(latencies, 95), pct(latencies, 99),
			latencies[len(latencies)-1], len(latencies))
	}
	fmt.Println("==========================================================")
	fmt.Println("Interpretation: under attack, a protected proxy should show most")
	fmt.Println("join/conn attempts as rejected/reset and keep latency low. Rising")
	fmt.Println("'engaged' + latency means traffic is getting through.")
}

func pct(sorted []time.Duration, p int) time.Duration {
	if len(sorted) == 0 {
		return 0
	}
	idx := (p * len(sorted)) / 100
	if idx >= len(sorted) {
		idx = len(sorted) - 1
	}
	return sorted[idx]
}

func progress(ctx context.Context, c *counters, done chan struct{}) {
	t := time.NewTicker(time.Second)
	defer t.Stop()
	var last int64
	for {
		select {
		case <-done:
			return
		case <-ctx.Done():
			return
		case <-t.C:
			cur := c.attempts.Load()
			log.Printf("  ... %d attempts (+%d/s) | engaged=%d rejected=%d reset=%d err=%d",
				cur, cur-last, c.ok.Load(), c.rejected.Load(), c.reset.Load(), c.errored.Load())
			last = cur
		}
	}
}

// --- latency collection (per-worker sinks merged at the end) ---

type latencySink struct{ samples []time.Duration }

type latencyCollector struct {
	mu    sync.Mutex
	sinks []*latencySink
}

func (lc *latencyCollector) newSink() *latencySink { return &latencySink{} }
func (ls *latencySink) add(d time.Duration)        { ls.samples = append(ls.samples, d) }

func (lc *latencyCollector) commit(ls *latencySink) {
	lc.mu.Lock()
	lc.sinks = append(lc.sinks, ls)
	lc.mu.Unlock()
}

func (lc *latencyCollector) merged() []time.Duration {
	lc.mu.Lock()
	defer lc.mu.Unlock()
	var all []time.Duration
	for _, s := range lc.sinks {
		all = append(all, s.samples...)
	}
	return all
}

const nameChars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_"

func randomName() string {
	b := make([]byte, 10)
	rand.Read(b)
	for i := range b {
		b[i] = nameChars[int(b[i])%len(nameChars)]
	}
	return "bot_" + string(b[:6])
}
