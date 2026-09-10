package main

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
)

func newTestServer() (*server, *[]string, *sync.Mutex) {
	var mu sync.Mutex
	var calls []string
	s := &server{
		token:     "secret",
		table:     "inet australis",
		set4:      "blocklist",
		set6:      "blocklist6",
		maxBanSec: 3600,
		nft: func(args ...string) error {
			mu.Lock()
			defer mu.Unlock()
			calls = append(calls, strings.Join(args, " "))
			return nil
		},
	}
	return s, &calls, &mu
}

func post(s *server, auth, body string) *httptest.ResponseRecorder {
	req := httptest.NewRequest(http.MethodPost, "/block", strings.NewReader(body))
	if auth != "" {
		req.Header.Set("Authorization", auth)
	}
	rr := httptest.NewRecorder()
	s.handleBlock(rr, req)
	return rr
}

func TestBlockRequiresAuth(t *testing.T) {
	s, calls, _ := newTestServer()
	rr := post(s, "", `{"ip":"1.2.3.4"}`)
	if rr.Code != http.StatusUnauthorized {
		t.Fatalf("code = %d, want 401", rr.Code)
	}
	if len(*calls) != 0 {
		t.Errorf("nft called %d times on unauthorized request, want 0", len(*calls))
	}
}

func TestBlockWrongToken(t *testing.T) {
	s, _, _ := newTestServer()
	rr := post(s, "Bearer nope", `{"ip":"1.2.3.4"}`)
	if rr.Code != http.StatusUnauthorized {
		t.Fatalf("code = %d, want 401", rr.Code)
	}
}

func TestBlockValidIPv4(t *testing.T) {
	s, calls, _ := newTestServer()
	rr := post(s, "Bearer secret", `{"ip":"203.0.113.7","ban_seconds":60,"reason":"flood"}`)
	if rr.Code != http.StatusNoContent {
		t.Fatalf("code = %d, want 204", rr.Code)
	}
	if len(*calls) != 1 {
		t.Fatalf("nft calls = %d, want 1", len(*calls))
	}
	got := (*calls)[0]
	if !strings.Contains(got, "blocklist") || !strings.Contains(got, "203.0.113.7") || !strings.Contains(got, "timeout 60s") {
		t.Errorf("unexpected nft args: %q", got)
	}
}

func TestBlockValidIPv6UsesSet6(t *testing.T) {
	s, calls, _ := newTestServer()
	rr := post(s, "Bearer secret", `{"ip":"2001:db8::1","ban_seconds":30}`)
	if rr.Code != http.StatusNoContent {
		t.Fatalf("code = %d, want 204", rr.Code)
	}
	if !strings.Contains((*calls)[0], "blocklist6") {
		t.Errorf("expected blocklist6 for IPv6, got %q", (*calls)[0])
	}
}

func TestBlockInvalidIP(t *testing.T) {
	s, calls, _ := newTestServer()
	rr := post(s, "Bearer secret", `{"ip":"not-an-ip"}`)
	if rr.Code != http.StatusBadRequest {
		t.Fatalf("code = %d, want 400", rr.Code)
	}
	if len(*calls) != 0 {
		t.Errorf("nft should not run for invalid ip")
	}
}

func TestBlockClampsBan(t *testing.T) {
	s, calls, _ := newTestServer()
	rr := post(s, "Bearer secret", `{"ip":"203.0.113.7","ban_seconds":999999}`)
	if rr.Code != http.StatusNoContent {
		t.Fatalf("code = %d, want 204", rr.Code)
	}
	if !strings.Contains((*calls)[0], "timeout 3600s") {
		t.Errorf("ban not clamped to max: %q", (*calls)[0])
	}
}

func TestBlockRejectsGet(t *testing.T) {
	s, _, _ := newTestServer()
	req := httptest.NewRequest(http.MethodGet, "/block", nil)
	req.Header.Set("Authorization", "Bearer secret")
	rr := httptest.NewRecorder()
	s.handleBlock(rr, req)
	if rr.Code != http.StatusMethodNotAllowed {
		t.Fatalf("code = %d, want 405", rr.Code)
	}
}
