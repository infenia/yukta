// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package client

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func eventServer(t *testing.T, wantQuery string, frames ...string) *httptest.Server {
	t.Helper()
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/events/stream" {
			t.Errorf("unexpected path %s", r.URL.Path)
		}
		if r.URL.RawQuery != wantQuery {
			t.Errorf("query = %q, want %q", r.URL.RawQuery, wantQuery)
		}
		w.Header().Set("Content-Type", "text/event-stream")
		for _, f := range frames {
			fmt.Fprintf(w, "data: %s\n\n", f)
		}
	}))
}

func TestStreamEvents_parsesEveryEventType(t *testing.T) {
	server := eventServer(t, "executionId=e1&type=LogLine",
		`{"type":"NodeStatusChanged","event":{"executionId":"e1","workflowId":"w","nodeId":"n1","module":"M","status":"RUNNING","metadata":{}}}`,
		`{"type":"WorkflowStatusChanged","event":{"executionId":"e1","workflowId":"w","status":"SUCCESS"}}`,
		`{"type":"ControlOutcome","event":{"executionId":"e1","command":"Pause","actor":"key:ops","applied":false,"reason":"nope"}}`,
		`{"type":"LogLine","event":{"executionId":"e1","nodeId":"n1","line":"hello"}}`)
	defer server.Close()

	var got []ExecutionEvent
	err := NewClient(server.URL).StreamEvents(context.Background(), "e1", "LogLine", "", func(e ExecutionEvent) error {
		got = append(got, e)
		return nil
	})
	if !endedUnexpectedly(err) { // the mock closes the feed after its events
		t.Fatalf("unexpected error: %v", err)
	}
	if len(got) != 4 {
		t.Fatalf("got %d events, want 4", len(got))
	}
	if got[0].Type != EventNodeStatusChanged || got[0].NodeID != "n1" || got[0].Status != "RUNNING" {
		t.Errorf("node event = %+v", got[0])
	}
	if got[1].Type != EventWorkflowStatusChanged || got[1].Status != "SUCCESS" {
		t.Errorf("workflow event = %+v", got[1])
	}
	if got[2].Type != EventControlOutcome || got[2].Applied == nil || *got[2].Applied ||
		got[2].Command != "Pause" || got[2].Actor != "key:ops" || got[2].Reason != "nope" {
		t.Errorf("control event = %+v", got[2])
	}
	if got[3].Type != EventLogLine || got[3].Line != "hello" || got[3].NodeID != "n1" {
		t.Errorf("log event = %+v", got[3])
	}
}

func TestStreamEvents_severityFilterAndEnvelopeFields(t *testing.T) {
	server := eventServer(t, "severity=WARN",
		`{"type":"NodeStatusChanged","severity":"ERROR","event":{"executionId":"e1","nodeId":"style","status":"FAILURE","metadata":{"error":"Process failed"}}}`)
	defer server.Close()

	var got []ExecutionEvent
	err := NewClient(server.URL).StreamEvents(context.Background(), "", "", "WARN", func(e ExecutionEvent) error {
		got = append(got, e)
		return nil
	})
	if !endedUnexpectedly(err) {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(got) != 1 || got[0].Severity != "ERROR" || got[0].Metadata["error"] != "Process failed" {
		t.Errorf("events = %+v", got)
	}
}

func TestStreamEvents_noFiltersSendsNoQuery(t *testing.T) {
	server := eventServer(t, "")
	defer server.Close()

	if err := NewClient(server.URL).StreamEvents(context.Background(), "", "", "", func(ExecutionEvent) error { return nil }); !endedUnexpectedly(err) {
		t.Fatalf("unexpected error: %v", err)
	}
}

func endedUnexpectedly(err error) bool {
	return err != nil && strings.Contains(err.Error(), "ended unexpectedly")
}

func TestStreamEvents_callbackErrorStopsStream(t *testing.T) {
	server := eventServer(t, "",
		`{"type":"LogLine","event":{"executionId":"e","nodeId":"n","line":"a"}}`,
		`{"type":"LogLine","event":{"executionId":"e","nodeId":"n","line":"b"}}`)
	defer server.Close()

	stop := errors.New("stop")
	calls := 0
	err := NewClient(server.URL).StreamEvents(context.Background(), "", "", "", func(ExecutionEvent) error {
		calls++
		return stop
	})
	if !errors.Is(err, stop) || calls != 1 {
		t.Errorf("err = %v, calls = %d; want stop after 1 call", err, calls)
	}
}

func TestStreamEvents_badJSON(t *testing.T) {
	server := eventServer(t, "", `not json`)
	defer server.Close()

	err := NewClient(server.URL).StreamEvents(context.Background(), "", "", "", func(ExecutionEvent) error { return nil })
	if err == nil {
		t.Fatal("expected an unmarshal error")
	}
}

func TestStreamEvents_nilCallback(t *testing.T) {
	if err := NewClient("http://localhost").StreamEvents(context.Background(), "", "", "", nil); err == nil {
		t.Fatal("expected error for nil callback")
	}
}

func TestStreamEvents_apiError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		http.Error(w, "nope", http.StatusInternalServerError)
	}))
	defer server.Close()

	err := NewClient(server.URL).StreamEvents(context.Background(), "", "", "", func(ExecutionEvent) error { return nil })
	if err == nil {
		t.Fatal("expected error for 500")
	}
}

func TestIsTerminalStatus(t *testing.T) {
	for status, want := range map[string]bool{
		"": false, "PENDING": false, "QUEUED": false, "RUNNING": false,
		"SUCCESS": true, "FAILURE": true, "ERROR": true, "STOPPED": true, "CANCELLED": true,
		"TIMED_OUT": true, "SKIPPED": true, "UPSTREAM_FAILED": true, "ABORTED": true,
	} {
		if got := IsTerminalStatus(status); got != want {
			t.Errorf("IsTerminalStatus(%q) = %t, want %t", status, got, want)
		}
	}
}
