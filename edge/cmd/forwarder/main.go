// Command forwarder is the Australis edge TCP forwarder (Layer 0, self-mode).
//
// It listens on the public game port of the edge box, and forwards each
// connection that survived the XDP/eBPF filter to the hidden origin. It
// preserves the real client IP to the origin using PROXY protocol v2, so the
// Velocity plugin can still rate-limit per real IP. Players only ever see the
// edge IP — the origin stays hidden.
//
// Standard library only, so it builds and runs anywhere with no dependencies.
//
// Example:
//
//	forwarder -listen :25565 -origin 10.8.0.1:25565 -proxy-protocol
package main

import (
	"flag"
	"io"
	"log"
	"net"
	"time"
)

func main() {
	listenAddr := flag.String("listen", ":25565", "public address to listen on")
	originAddr := flag.String("origin", "", "hidden origin address host:port (required)")
	useProxy := flag.Bool("proxy-protocol", true, "prepend PROXY protocol v2 header to origin")
	dialTimeout := flag.Duration("dial-timeout", 5*time.Second, "origin dial timeout")
	flag.Parse()

	if *originAddr == "" {
		log.Fatal("australis-forwarder: -origin is required")
	}

	ln, err := net.Listen("tcp", *listenAddr)
	if err != nil {
		log.Fatalf("australis-forwarder: listen %s: %v", *listenAddr, err)
	}
	log.Printf("australis-forwarder: %s -> %s (proxy-protocol=%v)", *listenAddr, *originAddr, *useProxy)

	for {
		client, err := ln.Accept()
		if err != nil {
			log.Printf("australis-forwarder: accept: %v", err)
			continue
		}
		go handle(client, *originAddr, *useProxy, *dialTimeout)
	}
}

func handle(client net.Conn, origin string, useProxy bool, dialTimeout time.Duration) {
	defer client.Close()

	if tcp, ok := client.(*net.TCPConn); ok {
		_ = tcp.SetNoDelay(true)
	}

	upstream, err := net.DialTimeout("tcp", origin, dialTimeout)
	if err != nil {
		log.Printf("australis-forwarder: dial origin %s: %v", origin, err)
		return
	}
	defer upstream.Close()
	if tcp, ok := upstream.(*net.TCPConn); ok {
		_ = tcp.SetNoDelay(true)
	}

	if useProxy {
		header, herr := buildProxyV2Header(client.RemoteAddr(), client.LocalAddr())
		if herr != nil {
			log.Printf("australis-forwarder: proxy header: %v", herr)
			return
		}
		if _, werr := upstream.Write(header); werr != nil {
			log.Printf("australis-forwarder: write proxy header: %v", werr)
			return
		}
	}

	// Pipe both directions; close when either side finishes.
	done := make(chan struct{}, 2)
	go pipe(upstream, client, done)
	go pipe(client, upstream, done)
	<-done
}

func pipe(dst io.Writer, src io.Reader, done chan<- struct{}) {
	_, _ = io.Copy(dst, src)
	done <- struct{}{}
}
