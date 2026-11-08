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
		{"GET /api/supervisors/status", c.GetSupervisors},
		{"GET /api/supervisors", c.ListSupervisors},
		{"GET /api/supervisors/s%2F1", func() (any, error) { return c.GetSupervisor("s/1") }},
		{"GET /api/supervisor-engines", c.GetSupervisorEngines},
		{"GET /api/supervisor-engines/rules", func() (any, error) { return c.GetSupervisorEngine("rules") }},
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

func TestAssignmentHelpersReportServerErrorsAndOddAnswers(t *testing.T) {
	answer := `{"data":"not a list"}`
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_, _ = w.Write([]byte(answer))
	}))
	defer srv.Close()
	c := NewClient(srv.URL)

	if _, err := c.Assign("s1", "server", true, false); err == nil {
		t.Error("an answer that is not a list must fail")
	}
	if _, err := c.SetParents("s1", nil, nil); err == nil {
		t.Error("a supervisor that is not an object must fail")
	}
	down := NewClient("http://127.0.0.1:1")
	for name, call := range map[string]func() (any, error){
		"assign":   func() (any, error) { return down.Assign("s", "server", true, false) },
		"unassign": func() (any, error) { return down.Unassign("s", "server", false) },
		"check":    func() (any, error) { return down.CheckAssign("s", []string{"server"}) },
		"parents":  func() (any, error) { return down.SetParents("s", nil, nil) },
	} {
		if _, err := call(); err == nil {
			t.Errorf("%s must fail when the server is down", name)
		}
	}
}
