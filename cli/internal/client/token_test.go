// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package client

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestToken_overPlainHttpToARemoteHostIsRefused(t *testing.T) {
	c := NewClient("http://prod.example.com")
	c.Token = "secret"

	_, err := c.newRequest("GET", "/api/sessions")

	if err == nil || !strings.Contains(err.Error(), "--insecure-token") {
		t.Fatalf("err=%v", err)
	}
	c.AllowInsecureToken = true
	if _, err := c.newRequest("GET", "/api/sessions"); err != nil {
		t.Errorf("override ignored: %v", err)
	}
}

func TestToken_allowedOnLoopbackHttpsAndWithoutAToken(t *testing.T) {
	for _, base := range []string{"http://localhost:9858", "http://127.0.0.1:1", "http://[::1]:1", "https://prod.example.com"} {
		c := NewClient(base)
		c.Token = "secret"
		if _, err := c.newRequest("GET", "/x"); err != nil {
			t.Errorf("%s: %v", base, err)
		}
	}
	if _, err := NewClient("http://prod.example.com").newRequest("GET", "/x"); err != nil {
		t.Errorf("no token should be fine: %v", err)
	}
}

func TestUnauthorized_hintsAtTheTokenSettings(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusUnauthorized)
	}))
	defer srv.Close()

	_, err := NewClient(srv.URL).GetSessions()

	if err == nil || !strings.Contains(err.Error(), "YUKTA_TOKEN") {
		t.Errorf("err=%v", err)
	}
}
