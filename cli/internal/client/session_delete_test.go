// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package client

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestDeleteSessionAndWorkflow_sendDeleteWithOptionalForce(t *testing.T) {
	var seen []string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		seen = append(seen, r.Method+" "+r.URL.RequestURI())
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"data":null}`))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	if err := c.DeleteSession("s 1", false); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if err := c.DeleteWorkflow("s1", "w1", true); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	want := []string{"DELETE /api/sessions/s%201", "DELETE /api/sessions/s1/workflows/w1?force=true"}
	if len(seen) != len(want) || seen[0] != want[0] || seen[1] != want[1] {
		t.Errorf("requests = %v, want %v", seen, want)
	}
}

func TestDeleteSession_conflictAndBadURL_returnErrors(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusConflict)
		_, _ = w.Write([]byte(`{"message":"2 run(s) are still active"}`))
	}))
	defer server.Close()

	if err := NewClient(server.URL).DeleteSession("s1", false); err == nil {
		t.Error("expected an error for a 409")
	}
	if err := NewClient("http://bad host\x7f").DeleteSession("s1", false); err == nil {
		t.Error("expected an error for an unusable base URL")
	}
}
