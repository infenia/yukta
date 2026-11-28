// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package client

import (
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestTestWorkflow(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost || r.URL.Path != "/api/sessions/s 1/workflows/w/test" {
			t.Errorf("request %s %q", r.Method, r.URL.Path)
		}
		_, _ = io.WriteString(w, `{"data":{"passed":false,"status":"FAILURE","assertions":[{"name":"status","passed":false,"detail":"FAILURE"}]}}`)
	}))
	defer srv.Close()

	report, err := NewClient(srv.URL).TestWorkflow("s 1", "w", map[string]any{"input": map[string]any{}})

	if err != nil || report.Passed || report.Status != "FAILURE" || report.Assertions[0].Detail != "FAILURE" {
		t.Errorf("report %+v err %v", report, err)
	}
}

func TestTestWorkflow_errors(t *testing.T) {
	c := NewClient("http://127.0.0.1:1")
	if _, err := c.TestWorkflow("", "w", nil); err == nil {
		t.Error("empty session")
	}
	if _, err := c.TestWorkflow("s", "", nil); err == nil {
		t.Error("empty workflow")
	}
	if _, err := c.TestWorkflow("s", "w", map[string]any{"bad": make(chan int)}); err == nil {
		t.Error("unencodable spec")
	}
	if _, err := c.TestWorkflow("s", "w", nil); err == nil {
		t.Error("unreachable server")
	}
	if _, err := NewClient("http://[::1").TestWorkflow("s", "w", nil); err == nil {
		t.Error("malformed base URL")
	}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_, _ = io.WriteString(w, `{"data":`)
	}))
	defer srv.Close()
	if _, err := NewClient(srv.URL).TestWorkflow("s", "w", nil); err == nil {
		t.Error("bad json")
	}
}
