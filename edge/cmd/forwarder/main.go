// Command forwarder is the Australis edge TCP forwarder (Layer 0, self-mode).
//
// It listens on the public game port of the edge box, and forwards each
// connection that survived the XDP/eBPF filter to the hidden origin. It
// preserves the real client IP to the origin using PROXY protocol v2, so the
// Velocity plugin can still rate-limit per real IP. Players only ever see the
// edge IP — the origin stays hidden.
//
// Because it sits on the public port of an anti-DDoS box, it defends itself:
//   - a global cap on concurrent proxied connections (fast-reject over cap),
//   - a per-source-IP connection cap (one IP can't exhaust the box),
//   - an idle/slowloris timeout that tears down stalled connections,
//   - graceful shutdown on SIGINT/SIGTERM (stop accepting, drain briefly),
//   - lightweight counters on an optional local metrics endpoint.
//
// Standard library only, so it builds and runs anywhere with no dependencies.
// Every flag also reads a default from an environment variable (LISTEN, ORIGIN,
// PROXY_PROTOCOL, DIAL_TIMEOUT, MAX_CONNS, MAX_CONNS_PER_IP, IDLE_TIMEOUT,
// DRAIN_TIMEOUT, METRICS) so the systemd unit can drive it entirely from
// /etc/australis/forwarder.env.
//
// Example:
//
//	forwarder -listen :25565 -origin 10.8.0.1:25565 -proxy-protocol
package main

import (
	"context"
	"errors"
	"flag"
	"log"
	"net"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"sync"
	"syscall"
	"time"
)

func main() {
	listenAddr := flag.String("listen", envOr("LISTEN", ":25565"), "public address to listen on")
	originAddr := flag.String("origin", envOr("ORIGIN", ""), "hidden origin address host:port (required)")
	useProxy := flag.Bool("proxy-protocol", envBoolOr("PROXY_PROTOCOL", true), "prepend PROXY protocol v2 header to origin")
	dialTimeout := flag.Duration("dial-timeout", envDurOr("DIAL_TIMEOUT", 5*time.Second), "origin dial timeout")
	maxConns := flag.Int("max-conns", envIntOr("MAX_CONNS", 8192), "max concurrent proxied connections (0 = unlimited); over cap, new accepts are closed immediately")
	maxConnsPerIP := flag.Int("max-conns-per-ip", envIntOr("MAX_CONNS_PER_IP", 64), "max concurrent connections from a single source IP (0 = unlimited)")
	idleTimeout := flag.Duration("idle-timeout", envDurOr("IDLE_TIMEOUT", 5*time.Minute), "tear down a connection idle longer than this (0 = disabled)")
	drainTimeout := flag.Duration("drain-timeout", envDurOr("DRAIN_TIMEOUT", 10*time.Second), "on shutdown, wait up to this long for in-flight connections to drain")
	metricsAddr := flag.String("metrics", envOr("METRICS", ""), "optional local address for a plaintext metrics endpoint, e.g. 127.0.0.1:9100 (empty = disabled)")
	metricsInterval := flag.Duration("metrics-log-interval", envDurOr("METRICS_LOG_INTERVAL", 0), "if >0, log a counter snapshot on this interval")
	flag.Parse()

	if *originAddr == "" {
		log.Fatal("australis-forwarder: -origin is required")
	}

	f := &forwarder{
		origin:       *originAddr,
		useProxy:     *useProxy,
		dialTimeout:  *dialTimeout,
		idleTimeout:  *idleTimeout,
		drainTimeout: *drainTimeout,
		lim:          newLimiter(*maxConns, *maxConnsPerIP),
		met:          &metrics{},
	}

	lnCfg := net.ListenConfig{}
	ln, err := lnCfg.Listen(context.Background(), "tcp", *listenAddr)
	if err != nil {
		log.Fatalf("australis-forwarder: listen %s: %v", *listenAddr, err)
	}
	log.Printf("australis-forwarder: %s -> %s (proxy-protocol=%v max-conns=%d max-conns-per-ip=%d idle-timeout=%s)",
		*listenAddr, *originAddr, *useProxy, *maxConns, *maxConnsPerIP, *idleTimeout)

	// Optional metrics endpoint.
	var metricsSrv *http.Server
	if *metricsAddr != "" {
		metricsSrv = f.met.startServer(*metricsAddr)
		log.Printf("australis-forwarder: metrics on http://%s/metrics", *metricsAddr)
	}

	// Optional periodic metrics log line.
	stopTicker := make(chan struct{})
	if *metricsInterval > 0 {
		go f.met.logEvery(*metricsInterval, stopTicker)
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	// Close the listener when a shutdown signal arrives so Accept unblocks and
	// serve returns.
	go func() {
		<-ctx.Done()
		log.Printf("australis-forwarder: shutdown signal received, no longer accepting")
		_ = ln.Close()
	}()

	f.serve(ln)

	// Drain: give in-flight connections a bounded window to finish.
	log.Printf("australis-forwarder: draining (up to %s), %d active", *drainTimeout, f.met.active.Load())
	f.drain(*drainTimeout)

	close(stopTicker)
	if metricsSrv != nil {
		shutCtx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		_ = metricsSrv.Shutdown(shutCtx)
		cancel()
	}
	log.Printf("australis-forwarder: stopped (%s)", f.met.logLine())
}

// forwarder holds the running configuration and shared state.
type forwarder struct {
	origin       string
	useProxy     bool
	dialTimeout  time.Duration
	idleTimeout  time.Duration
	drainTimeout time.Duration
	lim          *limiter
	met          *metrics
	wg           sync.WaitGroup // tracks in-flight handle() goroutines
}

// serve runs the accept loop until the listener is closed (on shutdown, or by a
// test). A closed listener is a clean exit; other errors are transient and get a
// short backoff so a persistent error can't spin the CPU.
func (f *forwarder) serve(ln net.Listener) {
	for {
		client, err := ln.Accept()
		if err != nil {
			if errors.Is(err, net.ErrClosed) {
				return
			}
			log.Printf("australis-forwarder: accept: %v", err)
			time.Sleep(10 * time.Millisecond)
			continue
		}
		f.met.accepts.Add(1)

		ip := remoteIP(client)
		release, reason, ok := f.lim.acquire(ip)
		if !ok {
			switch reason {
			case reasonGlobalCap:
				f.met.rejectedGlobal.Add(1)
			case reasonPerIPCap:
				f.met.rejectedPerIP.Add(1)
			}
			// Fast reject: close immediately without spawning a proxy goroutine
			// or dialing the origin.
			_ = client.Close()
			continue
		}

		f.met.active.Add(1)
		f.wg.Add(1)
		go func() {
			defer f.wg.Done()
			defer f.met.active.Add(-1)
			defer release()
			f.handle(client)
		}()
	}
}

// drain waits up to d for in-flight connections to finish.
func (f *forwarder) drain(d time.Duration) {
	done := make(chan struct{})
	go func() {
		f.wg.Wait()
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(d):
	}
}

func (f *forwarder) handle(client net.Conn) {
	if tcp, ok := client.(*net.TCPConn); ok {
		_ = tcp.SetNoDelay(true)
	}

	upstream, err := net.DialTimeout("tcp", f.origin, f.dialTimeout)
	if err != nil {
		f.met.originDialFailed.Add(1)
		log.Printf("australis-forwarder: dial origin %s: %v", f.origin, err)
		_ = client.Close()
		return
	}
	if tcp, ok := upstream.(*net.TCPConn); ok {
		_ = tcp.SetNoDelay(true)
	}

	if f.useProxy {
		header, herr := buildProxyV2Header(client.RemoteAddr(), client.LocalAddr())
		if herr != nil {
			log.Printf("australis-forwarder: proxy header: %v", herr)
			_ = client.Close()
			_ = upstream.Close()
			return
		}
		if _, werr := upstream.Write(header); werr != nil {
			log.Printf("australis-forwarder: write proxy header: %v", werr)
			_ = client.Close()
			_ = upstream.Close()
			return
		}
	}

	// Bidirectional half-close piping with idle timeout; closes both ends.
	splice(client, upstream, f.idleTimeout)
}

// startServer starts a tiny plaintext metrics HTTP server and returns it so the
// caller can shut it down.
func (m *metrics) startServer(addr string) *http.Server {
	mux := http.NewServeMux()
	mux.HandleFunc("/metrics", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
		_, _ = w.Write([]byte(m.text()))
	})
	mux.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte("ok\n"))
	})
	srv := &http.Server{
		Addr:         addr,
		Handler:      mux,
		ReadTimeout:  5 * time.Second,
		WriteTimeout: 5 * time.Second,
	}
	go func() {
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Printf("australis-forwarder: metrics server: %v", err)
		}
	}()
	return srv
}

// logEvery logs a counter snapshot every d until stop is closed.
func (m *metrics) logEvery(d time.Duration, stop <-chan struct{}) {
	t := time.NewTicker(d)
	defer t.Stop()
	for {
		select {
		case <-stop:
			return
		case <-t.C:
			log.Printf("australis-forwarder: %s", m.logLine())
		}
	}
}

// --- env-var fallbacks so systemd EnvironmentFile can drive flag defaults ---

func envOr(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

func envIntOr(key string, def int) int {
	if v := os.Getenv(key); v != "" {
		if n, err := strconv.Atoi(v); err == nil {
			return n
		}
		log.Printf("australis-forwarder: invalid %s=%q, using default %d", key, v, def)
	}
	return def
}

func envBoolOr(key string, def bool) bool {
	if v := os.Getenv(key); v != "" {
		if b, err := strconv.ParseBool(v); err == nil {
			return b
		}
		log.Printf("australis-forwarder: invalid %s=%q, using default %v", key, v, def)
	}
	return def
}

func envDurOr(key string, def time.Duration) time.Duration {
	if v := os.Getenv(key); v != "" {
		if d, err := time.ParseDuration(v); err == nil {
			return d
		}
		log.Printf("australis-forwarder: invalid %s=%q, using default %s", key, v, def)
	}
	return def
}
