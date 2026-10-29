// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package client

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

func whoamiServer(status int, body string) *httptest.Server {
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/me" {
			http.NotFound(w, r)
			return
		}
		w.WriteHeader(status)
		_, _ = w.Write([]byte(body))
	}))
}

func TestGetWhoami_success(t *testing.T) {
	server := whoamiServer(200, `{"data":{"authenticated":true,"keyName":"ci","role":"OPERATOR","sessions":["s1"],"authEnabled":true,"permissions":{"controlRuns":true}}}`)
	defer server.Close()

	who, err := NewClient(server.URL).GetWhoami()
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if !who.Authenticated || who.KeyName == nil || *who.KeyName != "ci" || who.Role != "OPERATOR" || !who.Permissions["controlRuns"] {
		t.Errorf("unexpected whoami: %+v", who)
	}
}

func TestGetWhoami_serverError(t *testing.T) {
	server := whoamiServer(500, `{"message":"boom"}`)
	defer server.Close()

	if _, err := NewClient(server.URL).GetWhoami(); err == nil {
		t.Error("expected an error for a 500")
	}
}

func TestGetWhoami_badJSON(t *testing.T) {
	server := whoamiServer(200, `not json`)
	defer server.Close()

	if _, err := NewClient(server.URL).GetWhoami(); err == nil {
		t.Error("expected an error for a body that is not JSON")
	}
}

func TestGetWhoami_badURL(t *testing.T) {
	if _, err := NewClient("http://bad host\x7f").GetWhoami(); err == nil {
		t.Error("expected an error for an unusable base URL")
	}
}
