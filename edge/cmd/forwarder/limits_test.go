package main

import (
	"bufio"
	"io"
	"net"
	"testing"
	"time"
)

// TestLimiterAcquire is a table-driven test of the global and per-IP caps. Each
// step acquires (or releases) a slot and asserts the outcome.
func TestLimiterAcquire(t *testing.T) {
	type step struct {
		op          string // "acquire" or "release"
		ip          string
		wantOK      bool
		wantReason  rejectReason
		releaseName string // for op=="acquire", store the release func under this key
	}

	tests := []struct {
		name      string
		maxGlobal int
		maxPerIP  int
		steps     []step
	}{
		{
			name:      "per-ip cap blocks third from same ip",
			maxGlobal: 100,
			maxPerIP:  2,
			steps: []step{
				{op: "acquire", ip: "1.1.1.1", wantOK: true, wantReason: reasonNone, releaseName: "a"},
				{op: "acquire", ip: "1.1.1.1", wantOK: true, wantReason: reasonNone, releaseName: "b"},
				{op: "acquire", ip: "1.1.1.1", wantOK: false, wantReason: reasonPerIPCap},
				// a different IP is unaffected by another IP's cap
				{op: "acquire", ip: "2.2.2.2", wantOK: true, wantReason: reasonNone, releaseName: "c"},
			},
		},
		{
			name:      "releasing frees a per-ip slot",
			maxGlobal: 100,
			maxPerIP:  1,
			steps: []step{
				{op: "acquire", ip: "9.9.9.9", wantOK: true, wantReason: reasonNone, releaseName: "x"},
				{op: "acquire", ip: "9.9.9.9", wantOK: false, wantReason: reasonPerIPCap},
				{op: "release", releaseName: "x"},
				{op: "acquire", ip: "9.9.9.9", wantOK: true, wantReason: reasonNone, releaseName: "y"},
			},
		},
		{
			name:      "global cap blocks across ips",
			maxGlobal: 2,
			maxPerIP:  100,
			steps: []step{
				{op: "acquire", ip: "1.1.1.1", wantOK: true, wantReason: reasonNone, releaseName: "a"},
				{op: "acquire", ip: "2.2.2.2", wantOK: true, wantReason: reasonNone, releaseName: "b"},
				{op: "acquire", ip: "3.3.3.3", wantOK: false, wantReason: reasonGlobalCap},
				{op: "release", releaseName: "a"},
				{op: "acquire", ip: "3.3.3.3", wantOK: true, wantReason: reasonNone, releaseName: "c"},
			},
		},
		{
			name:      "zero caps mean unlimited",
			maxGlobal: 0,
			maxPerIP:  0,
			steps: []step{
				{op: "acquire", ip: "1.1.1.1", wantOK: true, wantReason: reasonNone, releaseName: "a"},
				{op: "acquire", ip: "1.1.1.1", wantOK: true, wantReason: reasonNone, releaseName: "b"},
				{op: "acquire", ip: "1.1.1.1", wantOK: true, wantReason: reasonNone, releaseName: "c"},
			},
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			l := newLimiter(tc.maxGlobal, tc.maxPerIP)
			releases := map[string]func(){}
			for i, s := range tc.steps {
				switch s.op {
				case "acquire":
					rel, reason, ok := l.acquire(s.ip)
					if ok != s.wantOK {
						t.Fatalf("step %d acquire(%s): ok=%v, want %v", i, s.ip, ok, s.wantOK)
					}
					if reason != s.wantReason {
						t.Fatalf("step %d acquire(%s): reason=%d, want %d", i, s.ip, reason, s.wantReason)
					}
					if ok {
						if rel == nil {
							t.Fatalf("step %d acquire(%s): ok but nil release", i, s.ip)
						}
						if s.releaseName != "" {
							releases[s.releaseName] = rel
						}
					} else if rel != nil {
						t.Fatalf("step %d acquire(%s): not ok but non-nil release", i, s.ip)
					}
				case "release":
					rel, found := releases[s.releaseName]
					if !found {
						t.Fatalf("step %d release: no release named %q", i, s.releaseName)
					}
					rel()
				}
			}
		})
	}
}

// TestLimiterReleaseIdempotent ensures a double release doesn't corrupt counts.
func TestLimiterReleaseIdempotent(t *testing.T) {
	l := newLimiter(10, 10)
	rel, _, ok := l.acquire("5.5.5.5")
	if !ok {
		t.Fatal("acquire failed")
	}
	if g, p := l.counts("5.5.5.5"); g != 1 || p != 1 {
		t.Fatalf("counts = (%d,%d), want (1,1)", g, p)
	}
	rel()
	rel() // second call must be a no-op
	if g, p := l.counts("5.5.5.5"); g != 0 || p != 0 {
		t.Fatalf("counts after double release = (%d,%d), want (0,0)", g, p)
	}
}

// --- integration-style tests over real loopback sockets ---

// echoOrigin starts a TCP server that reads the PROXY v2 header (if present) then
// echoes everything else back. It returns its address and a stop func.
func echoOrigin(t *testing.T, expectProxyHeader bool) (string, func()) {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("origin listen: %v", err)
	}
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func(c net.Conn) {
				defer c.Close()
				r := bufio.NewReader(c)
				if expectProxyHeader {
					// Read and discard the 16-byte-min PROXY v2 header. IPv4
					// header from loopback is 28 bytes; read exactly that.
					hdr := make([]byte, 28)
					if _, err := io.ReadFull(r, hdr); err != nil {
						return
					}
				}
				_, _ = io.Copy(c, r)
			}(c)
		}
	}()
	return ln.Addr().String(), func() { _ = ln.Close() }
}

// startForwarder wires a forwarder on a random loopback port and runs its accept
// loop in the background, returning the listen address and a shutdown func.
func startForwarder(t *testing.T, f *forwarder) (string, func()) {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("forwarder listen: %v", err)
	}
	done := make(chan struct{})
	go func() {
		// serve returns when the listener is closed.
		f.serve(ln)
		close(done)
	}()
	return ln.Addr().String(), func() {
		_ = ln.Close()
		<-done
		f.drain(time.Second)
	}
}

func TestForwarderProxiesAndPrependsHeader(t *testing.T) {
	originAddr, stopOrigin := echoOrigin(t, true)
	defer stopOrigin()

	f := &forwarder{
		origin:      originAddr,
		useProxy:    true,
		dialTimeout: 2 * time.Second,
		idleTimeout: 2 * time.Second,
		lim:         newLimiter(10, 10),
		met:         &metrics{},
	}
	addr, stop := startForwarder(t, f)
	defer stop()

	c, err := net.Dial("tcp", addr)
	if err != nil {
		t.Fatalf("dial forwarder: %v", err)
	}
	defer c.Close()

	msg := []byte("hello australis")
	if _, err := c.Write(msg); err != nil {
		t.Fatalf("write: %v", err)
	}
	_ = c.SetReadDeadline(time.Now().Add(2 * time.Second))
	got := make([]byte, len(msg))
	if _, err := io.ReadFull(c, got); err != nil {
		t.Fatalf("read echo: %v", err)
	}
	if string(got) != string(msg) {
		t.Fatalf("echo = %q, want %q", got, msg)
	}
	if f.met.accepts.Load() != 1 {
		t.Errorf("accepts = %d, want 1", f.met.accepts.Load())
	}
}

func TestForwarderPerIPCapRejectsFast(t *testing.T) {
	originAddr, stopOrigin := echoOrigin(t, false)
	defer stopOrigin()

	f := &forwarder{
		origin:      originAddr,
		useProxy:    false,
		dialTimeout: 2 * time.Second,
		idleTimeout: 5 * time.Second,
		lim:         newLimiter(100, 2), // per-IP cap of 2
		met:         &metrics{},
	}
	addr, stop := startForwarder(t, f)
	defer stop()

	// Two connections from this (loopback) IP should be accepted and stay open.
	var held []net.Conn
	defer func() {
		for _, c := range held {
			_ = c.Close()
		}
	}()
	for i := 0; i < 2; i++ {
		c, err := net.Dial("tcp", addr)
		if err != nil {
			t.Fatalf("dial %d: %v", i, err)
		}
		held = append(held, c)
	}

	// Give the accept loop a moment to register the two live connections.
	waitFor(t, time.Second, func() bool { return f.met.active.Load() == 2 })

	// The third connection from the same IP must be rejected: the forwarder
	// closes it immediately, so a read returns EOF quickly.
	c3, err := net.Dial("tcp", addr)
	if err != nil {
		t.Fatalf("dial 3: %v", err)
	}
	defer c3.Close()
	_ = c3.SetReadDeadline(time.Now().Add(2 * time.Second))
	buf := make([]byte, 1)
	_, rerr := c3.Read(buf)
	if rerr != io.EOF {
		t.Fatalf("third conn read err = %v, want EOF (fast reject)", rerr)
	}
	waitFor(t, time.Second, func() bool { return f.met.rejectedPerIP.Load() == 1 })
	if f.met.rejectedPerIP.Load() != 1 {
		t.Errorf("rejectedPerIP = %d, want 1", f.met.rejectedPerIP.Load())
	}
}

func TestForwarderGlobalCapRejectsFast(t *testing.T) {
	originAddr, stopOrigin := echoOrigin(t, false)
	defer stopOrigin()

	f := &forwarder{
		origin:      originAddr,
		useProxy:    false,
		dialTimeout: 2 * time.Second,
		idleTimeout: 5 * time.Second,
		lim:         newLimiter(1, 100), // global cap of 1
		met:         &metrics{},
	}
	addr, stop := startForwarder(t, f)
	defer stop()

	c1, err := net.Dial("tcp", addr)
	if err != nil {
		t.Fatalf("dial 1: %v", err)
	}
	defer c1.Close()
	waitFor(t, time.Second, func() bool { return f.met.active.Load() == 1 })

	c2, err := net.Dial("tcp", addr)
	if err != nil {
		t.Fatalf("dial 2: %v", err)
	}
	defer c2.Close()
	_ = c2.SetReadDeadline(time.Now().Add(2 * time.Second))
	buf := make([]byte, 1)
	if _, rerr := c2.Read(buf); rerr != io.EOF {
		t.Fatalf("over-cap conn read err = %v, want EOF (fast reject)", rerr)
	}
	waitFor(t, time.Second, func() bool { return f.met.rejectedGlobal.Load() == 1 })
	if f.met.rejectedGlobal.Load() != 1 {
		t.Errorf("rejectedGlobal = %d, want 1", f.met.rejectedGlobal.Load())
	}
}

func TestForwarderIdleTimeoutClosesConn(t *testing.T) {
	originAddr, stopOrigin := echoOrigin(t, false)
	defer stopOrigin()

	f := &forwarder{
		origin:      originAddr,
		useProxy:    false,
		dialTimeout: 2 * time.Second,
		idleTimeout: 150 * time.Millisecond, // very short idle timeout
		lim:         newLimiter(10, 10),
		met:         &metrics{},
	}
	addr, stop := startForwarder(t, f)
	defer stop()

	c, err := net.Dial("tcp", addr)
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	defer c.Close()

	// Send nothing (slowloris). The idle timeout should tear the connection down;
	// our read then returns EOF well within the deadline.
	_ = c.SetReadDeadline(time.Now().Add(2 * time.Second))
	buf := make([]byte, 1)
	start := time.Now()
	_, rerr := c.Read(buf)
	if rerr != io.EOF {
		t.Fatalf("read err = %v, want EOF after idle timeout", rerr)
	}
	if elapsed := time.Since(start); elapsed > time.Second {
		t.Errorf("idle teardown took %s, expected well under 1s", elapsed)
	}
}

// TestForwarderDrainWaits confirms drain returns promptly once conns close.
func TestForwarderDrainWaits(t *testing.T) {
	originAddr, stopOrigin := echoOrigin(t, false)
	defer stopOrigin()

	f := &forwarder{
		origin:      originAddr,
		useProxy:    false,
		dialTimeout: 2 * time.Second,
		idleTimeout: 5 * time.Second,
		lim:         newLimiter(10, 10),
		met:         &metrics{},
	}
	addr, stop := startForwarder(t, f)

	c, err := net.Dial("tcp", addr)
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	waitFor(t, time.Second, func() bool { return f.met.active.Load() == 1 })
	_ = c.Close()  // client closes -> handle goroutine should finish
	stop()         // stops accept loop + drains(1s)
	if f.met.active.Load() != 0 {
		t.Errorf("active after drain = %d, want 0", f.met.active.Load())
	}
}

// waitFor polls cond until it is true or the timeout elapses.
func waitFor(t *testing.T, d time.Duration, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(d)
	for time.Now().Before(deadline) {
		if cond() {
			return
		}
		time.Sleep(5 * time.Millisecond)
	}
	if !cond() {
		t.Fatalf("condition not met within %s", d)
	}
}
