// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package client

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

// callServer answers every request with data and records the method, path and query.
func callServer(t *testing.T, data string, method, path, query *string) *httptest.Server {
	t.Helper()
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		*method, *path, *query = r.Method, r.URL.Path, r.URL.RawQuery
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"data":` + data + `}`))
	}))
}

func TestGetExecution(t *testing.T) {
	var method, path, query string
	server := callServer(t, `{"executionId":"e1","workflowId":"w","status":"SUCCESS","tasks":[{"nodeId":"n","status":"SUCCESS"}]}`,
		&method, &path, &query)
	defer server.Close()

	p, err := NewClient(server.URL).GetExecution("e1")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if method != "GET" || path != "/api/executions/e1" {
		t.Errorf("request = %s %s", method, path)
	}
	if p.Status != "SUCCESS" || len(p.Tasks) != 1 || p.Tasks[0].NodeID != "n" {
		t.Errorf("progress = %+v", p)
	}
}

func TestGetExecution_emptyID(t *testing.T) {
	if _, err := NewClient("http://localhost").GetExecution(""); err == nil {
		t.Fatal("expected error")
	}
}

func TestRetryNode(t *testing.T) {
	var method, path, query string
	server := callServer(t, `{"executionId":"new-1"}`, &method, &path, &query)
	defer server.Close()

	r, err := NewClient(server.URL).RetryNode("e 1", "n/1")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if method != "POST" || path != "/api/executions/e 1/nodes/n/1/retry" {
		t.Errorf("request = %s %s", method, path)
	}
	if r.ExecutionID != "new-1" {
		t.Errorf("response = %+v", r)
	}
}

func TestSignalNode(t *testing.T) {
	var method, path, query string
	server := callServer(t, `{"executionId":"e1"}`, &method, &path, &query)
	defer server.Close()

	if _, err := NewClient(server.URL).SignalNode("e1", "n1", "approval", "approve", "ok then"); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if method != "POST" || path != "/api/executions/e1/nodes/n1/signal" {
		t.Errorf("request = %s %s", method, path)
	}
	if query != "data=ok+then&decision=approve&name=approval" {
		t.Errorf("query = %q", query)
	}
}

func TestSignalNode_validation(t *testing.T) {
	c := NewClient("http://localhost")
	if _, err := c.SignalNode("", "n", "a", "b", ""); err == nil {
		t.Error("expected error for empty execution")
	}
	if _, err := c.SignalNode("e", "n", "", "b", ""); err == nil {
		t.Error("expected error for empty name")
	}
	if _, err := c.SignalNode("e", "n", "a", "", ""); err == nil {
		t.Error("expected error for empty decision")
	}
}

func TestBreakpoint(t *testing.T) {
	var method, path, query string
	server := callServer(t, `{"executionId":"e1"}`, &method, &path, &query)
	defer server.Close()
	c := NewClient(server.URL)

	if _, err := c.SetBreakpoint("e1", "n1", "payload.status", "error"); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if method != "POST" || path != "/api/executions/e1/nodes/n1/breakpoint" || query != "equals=error&field=payload.status" {
		t.Errorf("set request = %s %s ?%s", method, path, query)
	}

	if _, err := c.ClearBreakpoint("e1", "n1"); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if method != "DELETE" || path != "/api/executions/e1/nodes/n1/breakpoint" {
		t.Errorf("clear request = %s %s", method, path)
	}

	if _, err := c.SetBreakpoint("e1", "n1", "", "x"); err == nil {
		t.Error("expected error for empty field")
	}
}

func TestExecutionCalls_validation(t *testing.T) {
	c := NewClient("http://localhost")
	if _, err := c.RetryNode("", "n"); err == nil {
		t.Error("RetryNode: expected error for empty execution")
	}
	if _, err := c.SetBreakpoint("", "n", "f", "v"); err == nil {
		t.Error("SetBreakpoint: expected error for empty execution")
	}
	if _, err := c.ClearBreakpoint("e", ""); err == nil {
		t.Error("ClearBreakpoint: expected error for empty node")
	}
}

func TestExecutionCalls_badBaseURL(t *testing.T) {
	c := NewClient("http://bad\x7fhost")
	if _, err := c.GetExecution("e"); err == nil {
		t.Error("GetExecution: expected request error")
	}
	if _, err := c.RetryNode("e", "n"); err == nil {
		t.Error("RetryNode: expected request error")
	}
}

func TestExecutionCalls_transportAndDecodeErrors(t *testing.T) {
	notJSON := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_, _ = w.Write([]byte("not json"))
	}))
	defer notJSON.Close()
	c := NewClient(notJSON.URL)
	if _, err := c.GetExecution("e"); err == nil {
		t.Error("GetExecution: expected decode error")
	}
	if _, err := c.RetryNode("e", "n"); err == nil {
		t.Error("RetryNode: expected decode error")
	}

	down := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {}))
	url := down.URL
	down.Close()
	if _, err := NewClient(url).GetExecution("e"); err == nil {
		t.Error("GetExecution: expected connection error")
	}
}

func TestExecutionCall_apiError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		http.Error(w, "nope", http.StatusConflict)
	}))
	defer server.Close()

	if _, err := NewClient(server.URL).RetryNode("e", "n"); err == nil {
		t.Fatal("expected error for 409")
	}
}

func TestGetNodeOutput(t *testing.T) {
	var method, path, query string
	server := callServer(t, `{"nodeId":"n","stream":"all","totalLines":3,"matchedLines":1,"truncated":false,"nextOffset":7,"lines":[{"number":2,"text":"boom"}]}`,
		&method, &path, &query)
	defer server.Close()

	out, err := NewClient(server.URL).GetNodeOutput("e1", "n", NodeOutputQuery{Stream: "stderr", Tail: 5, Head: -1, Offset: -1, Limit: 10, Grep: "a b", Context: 0})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if method != "GET" || path != "/api/executions/e1/nodes/n/output" {
		t.Errorf("request = %s %s", method, path)
	}
	for _, want := range []string{"stream=stderr", "tail=5", "limit=10", "context=0", "grep=a+b"} {
		if !contains(query, want) {
			t.Errorf("query %q lacks %q", query, want)
		}
	}
	if contains(query, "head=") || contains(query, "offset=") {
		t.Errorf("unset values were sent: %q", query)
	}
	if out.TotalLines != 3 || len(out.Lines) != 1 || out.Lines[0].Text != "boom" || out.NextOffset == nil || *out.NextOffset != 7 {
		t.Errorf("output = %+v", out)
	}
}

func TestGetNodeOutput_noQueryAndBadInput(t *testing.T) {
	var method, path, query string
	server := callServer(t, `{"lines":[]}`, &method, &path, &query)
	defer server.Close()
	c := NewClient(server.URL)

	if _, err := c.GetNodeOutput("e1", "n", NodeOutputQuery{Tail: -1, Head: -1, Offset: -1, Limit: -1, Context: -1}); err != nil || query != "" {
		t.Errorf("err=%v query=%q", err, query)
	}
	if _, err := c.GetNodeOutput("", "n", NodeOutputQuery{}); err == nil {
		t.Error("expected an error for an empty execution id")
	}
	if _, err := c.GetNodeOutput("e1", "", NodeOutputQuery{}); err == nil {
		t.Error("expected an error for an empty node id")
	}
}

func TestGetNodeOutput_serverErrorAndBadJSON(t *testing.T) {
	bad := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { _, _ = w.Write([]byte("not json")) }))
	defer bad.Close()
	if _, err := NewClient(bad.URL).GetNodeOutput("e1", "n", NodeOutputQuery{}); err == nil {
		t.Error("expected a decode error")
	}
	down := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { http.Error(w, "no", http.StatusNotFound) }))
	defer down.Close()
	if _, err := NewClient(down.URL).GetNodeOutput("e1", "n", NodeOutputQuery{}); err == nil {
		t.Error("expected an API error")
	}
	if _, err := NewClient("http://127.0.0.1:1\x7f").GetNodeOutput("e1", "n", NodeOutputQuery{}); err == nil {
		t.Error("expected a request error")
	}
}
