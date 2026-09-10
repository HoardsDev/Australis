package main

import (
	"fmt"
	"net"
	"sync"
	"sync/atomic"
	"time"
)

// rejectReason explains why a freshly accepted connection was refused.
type rejectReason int

const (
	reasonNone rejectReason = iota
	reasonGlobalCap
	reasonPerIPCap
)

// metrics holds lightweight lock-free counters for the forwarder. All fields are
// accessed via sync/atomic so they are safe to read from the metrics HTTP
// handler while the accept loop mutates them.
type metrics struct {
	accepts          atomic.Int64 // connections accepted from the listener
	active           atomic.Int64 // connections currently being proxied
	rejectedGlobal   atomic.Int64 // rejected: over the global concurrency cap
	rejectedPerIP    atomic.Int64 // rejected: over the per-source-IP cap
	originDialFailed atomic.Int64 // origin dial failures
}

// limiter enforces a global concurrency cap and a per-source-IP cap. It is safe
// for concurrent use. A zero cap means "unlimited" for that dimension.
type limiter struct {
	maxGlobal int
	maxPerIP  int

	mu       sync.Mutex
	global   int
	perIP    map[string]int
}

func newLimiter(maxGlobal, maxPerIP int) *limiter {
	return &limiter{
		maxGlobal: maxGlobal,
		maxPerIP:  maxPerIP,
		perIP:     make(map[string]int),
	}
}

// acquire reserves a slot for a connection from ip. On success it returns a
// release function that must be called exactly once when the connection ends,
// reasonNone, and true. On failure it returns a nil release func, the reason the
// slot was refused, and false.
func (l *limiter) acquire(ip string) (func(), rejectReason, bool) {
	l.mu.Lock()
	defer l.mu.Unlock()

	if l.maxGlobal > 0 && l.global >= l.maxGlobal {
		return nil, reasonGlobalCap, false
	}
	if l.maxPerIP > 0 && l.perIP[ip] >= l.maxPerIP {
		return nil, reasonPerIPCap, false
	}

	l.global++
	l.perIP[ip]++

	var once sync.Once
	release := func() {
		once.Do(func() {
			l.mu.Lock()
			defer l.mu.Unlock()
			l.global--
			if l.perIP[ip] <= 1 {
				delete(l.perIP, ip)
			} else {
				l.perIP[ip]--
			}
		})
	}
	return release, reasonNone, true
}

// counts returns the current global and per-ip counts (for tests/introspection).
func (l *limiter) counts(ip string) (global, perIP int) {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.global, l.perIP[ip]
}

// halfCloser is implemented by *net.TCPConn: it lets us signal EOF to the peer
// on one direction while still reading the other (proper TCP half-close).
type halfCloser interface {
	CloseWrite() error
}

// pipe copies from src to dst until src reports EOF or an error. If idle > 0, a
// read deadline of idle is applied before each read so a stalled/slowloris
// connection is torn down instead of pinning a goroutine forever. When src
// finishes cleanly it half-closes dst's write side (CloseWrite) so the peer sees
// a normal EOF rather than an abrupt reset. It signals completion on wg.
func pipe(dst, src net.Conn, idle time.Duration, wg *sync.WaitGroup) {
	defer wg.Done()

	buf := make([]byte, 32*1024)
	for {
		if idle > 0 {
			_ = src.SetReadDeadline(time.Now().Add(idle))
		}
		n, rerr := src.Read(buf)
		if n > 0 {
			if _, werr := dst.Write(buf[:n]); werr != nil {
				break
			}
		}
		if rerr != nil {
			break
		}
	}

	// Signal EOF to the destination's read side; fall back to a full close for
	// non-TCP connections that cannot half-close.
	if hc, ok := dst.(halfCloser); ok {
		_ = hc.CloseWrite()
	} else {
		_ = dst.Close()
	}
}

// splice proxies bidirectionally between a and b using half-close piping, then
// closes both. It blocks until both directions are done.
func splice(a, b net.Conn, idle time.Duration) {
	var wg sync.WaitGroup
	wg.Add(2)
	go pipe(a, b, idle, &wg)
	go pipe(b, a, idle, &wg)
	wg.Wait()
	_ = a.Close()
	_ = b.Close()
}

// remoteIP extracts the source IP (without port) from a connection's remote
// address for per-IP accounting.
func remoteIP(c net.Conn) string {
	if host, _, err := net.SplitHostPort(c.RemoteAddr().String()); err == nil {
		return host
	}
	return c.RemoteAddr().String()
}

// text renders the counters as a tiny Prometheus-style plaintext exposition.
func (m *metrics) text() string {
	return fmt.Sprintf(
		"australis_forwarder_accepts_total %d\n"+
			"australis_forwarder_active %d\n"+
			"australis_forwarder_rejected_total{reason=\"global_cap\"} %d\n"+
			"australis_forwarder_rejected_total{reason=\"per_ip_cap\"} %d\n"+
			"australis_forwarder_origin_dial_failures_total %d\n",
		m.accepts.Load(),
		m.active.Load(),
		m.rejectedGlobal.Load(),
		m.rejectedPerIP.Load(),
		m.originDialFailed.Load(),
	)
}

// logLine renders a compact single-line snapshot for periodic logging.
func (m *metrics) logLine() string {
	return fmt.Sprintf(
		"accepts=%d active=%d rejected_global=%d rejected_per_ip=%d origin_dial_failures=%d",
		m.accepts.Load(),
		m.active.Load(),
		m.rejectedGlobal.Load(),
		m.rejectedPerIP.Load(),
		m.originDialFailed.Load(),
	)
}
