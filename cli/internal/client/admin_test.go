// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package client

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestAdminCalls(t *testing.T) {
	var got string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		got = r.Method + " " + r.URL.RequestURI()
		_, _ = w.Write([]byte(`{"data":[]}`))
	}))
	defer srv.Close()
	c := NewClient(srv.URL)

	tests := []struct {
		want string
		call func() (any, error)
	}{
		{"GET /api/supervisors", c.GetSupervisors},
		{"GET /api/workers", c.GetWorkers},
		{"GET /api/sessions/s/config", func() (any, error) { return c.GetSessionConfig("s") }},
		{"GET /api/control/audit", func() (any, error) { return c.GetControlAudit(0, "") }},
		{"GET /api/control/audit?executionId=e&limit=5", func() (any, error) { return c.GetControlAudit(5, "e") }},
		{"GET /api/executions?filter=f&limit=2&sessionId=s", func() (any, error) { return c.SearchExecutions("s", "f", 2) }},
		{"POST /api/executions/e/recover", func() (any, error) { return c.RecoverExecution("e") }},
		{"POST /api/executions/e/priority?priority=7", func() (any, error) { return c.SetExecutionPriority("e", 7) }},
	}
	for _, tt := range tests {
		if _, err := tt.call(); err != nil || got != tt.want {
			t.Errorf("want %q, got %q (%v)", tt.want, got, err)
		}
	}
}
