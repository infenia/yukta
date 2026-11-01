// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package controlbus

import (
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"com.infenia.yukta/go-cli/internal/client"
	"com.infenia.yukta/go-cli/internal/commands"
	"com.infenia.yukta/go-cli/internal/testutil"
)

const watchStream = "data: " + `{"type":"NodeStatusChanged","event":{"executionId":"e","nodeId":"spotless","status":"SUCCESS"}}` + "\n\n" +
	"data: " + `{"type":"LogLine","event":{"executionId":"e","nodeId":"spotless","line":"all good"}}` + "\n\n" +
	"data: " + `{"type":"ControlOutcome","event":{"executionId":"e","command":"Pause","applied":false,"reason":"denied"}}` + "\n\n" +
	"data: " + `{"type":"WorkflowStatusChanged","event":{"executionId":"e","status":"FAILURE"}}` + "\n\n" +
	"data: " + `{"type":"LogLine","event":{"executionId":"e","nodeId":"late","line":"after the end"}}` + "\n\n"

func TestWatchCmd_formatsEvents(t *testing.T) {
	out, err := testutil.Run(t, WatchCmd(testutil.Stream(t, watchStream)))
	if err != nil && !feedEnded(err) {
		t.Fatal(err)
	}
	want := "NODE spotless SUCCESS\nLOG spotless: all good\nCONTROL Pause applied=false denied\nWORKFLOW FAILURE\nLOG late: after the end\n"
	if out != want {
		t.Errorf("output:\n%q\nwant:\n%q", out, want)
	}
}

func TestWatchCmd_untilDoneStopsAtTerminalWorkflowEvent(t *testing.T) {
	out, err := testutil.Run(t, WatchCmd(testutil.Stream(t, watchStream)), "--execution", "e", "--until-done", "--no-logs")
	if err != nil && !feedEnded(err) {
		t.Fatal(err)
	}
	want := "NODE spotless SUCCESS\nCONTROL Pause applied=false denied\nWORKFLOW FAILURE\n"
	if out != want {
		t.Errorf("output:\n%q\nwant:\n%q", out, want)
	}
}

func TestWatchCmd_quietShowsOnlyProblemsAndTheEnd(t *testing.T) {
	stream := watchStream +
		"data: " + `{"type":"NodeStatusChanged","event":{"executionId":"e","nodeId":"tests","status":"RUNNING"}}` + "\n\n" +
		"data: " + `{"type":"NodeStatusChanged","event":{"executionId":"e","nodeId":"tests","status":"FAILURE"}}` + "\n\n" +
		"data: " + `{"type":"ControlOutcome","event":{"executionId":"e","command":"Stop","applied":true}}` + "\n\n" +
		"data: " + `{"type":"Unknown","event":{}}` + "\n\n"
	out, err := testutil.Run(t, WatchCmd(testutil.Stream(t, stream)), "--quiet")
	if err != nil && !feedEnded(err) {
		t.Fatal(err)
	}
	want := "CONTROL Pause applied=false denied\nWORKFLOW FAILURE\nNODE tests FAILURE\n"
	if out != want {
		t.Errorf("output:\n%q\nwant:\n%q", out, want)
	}
}

func TestWatchCmd_typeFilter(t *testing.T) {
	// the server filters by type; this fake sends everything, so the client filter applies too
	out, err := testutil.Run(t, WatchCmd(testutil.Stream(t, watchStream)), "--type", "controloutcome")
	if err != nil && !feedEnded(err) {
		t.Fatal(err)
	}
	if out != "CONTROL Pause applied=false denied\n" {
		t.Errorf("output %q", out)
	}
}

func TestWatchCmd_jsonOutput(t *testing.T) {
	commands.SetTestOutputFormat("json")
	defer commands.SetTestOutputFormat("table")

	out, err := testutil.Run(t, WatchCmd(testutil.Stream(t, watchStream)), "--no-logs")
	if err != nil && !feedEnded(err) {
		t.Fatal(err)
	}
	lines := strings.Split(strings.TrimSpace(out), "\n")
	if len(lines) != 3 || !strings.Contains(lines[0], `"type":"NodeStatusChanged"`) ||
		!strings.Contains(lines[1], `"applied":false`) {
		t.Errorf("output %q", out)
	}
}

func TestWatchCmd_untilDoneNeedsExecution(t *testing.T) {
	_, err := testutil.Run(t, WatchCmd(client.NewClient("http://localhost:1")), "--until-done")
	if err == nil || !strings.Contains(err.Error(), "--execution") {
		t.Errorf("expected --execution error, got %v", err)
	}
}

// A run that ended before the watch began produces no events; the status snapshot reports it.
func TestWatchCmd_untilDoneReportsAlreadyFinishedRun(t *testing.T) {
	defer func(old time.Duration) { snapshotInterval = old }(snapshotInterval)
	snapshotInterval = 20 * time.Millisecond

	release := make(chan struct{})
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/events/stream":
			w.Header().Set("Content-Type", "text/event-stream")
			w.(http.Flusher).Flush()
			select { // stay open and silent until the test is over
			case <-release:
			case <-r.Context().Done():
			}
		case "/api/executions/e":
			w.Header().Set("Content-Type", "application/json")
			_, _ = io.WriteString(w, `{"data":{"executionId":"e","workflowId":"w","status":"SUCCESS"}}`)
		default:
			t.Errorf("unexpected path %s", r.URL.Path)
		}
	}))
	defer srv.Close()
	defer close(release)

	done := make(chan struct{})
	var out string
	var err error
	go func() {
		out, err = testutil.Run(t, WatchCmd(client.NewClient(srv.URL)), "--execution", "e", "--until-done")
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("watch did not exit for a finished run")
	}
	if err != nil {
		t.Fatal(err)
	}
	if out != "WORKFLOW SUCCESS\n" {
		t.Errorf("output %q", out)
	}
}

// A watch that attaches late gets the nodes that already ended from the snapshot, once.
func TestWatchCmd_untilDoneReplaysEndedNodes(t *testing.T) {
	defer func(old time.Duration) { snapshotInterval = old }(snapshotInterval)
	snapshotInterval = 20 * time.Millisecond

	release := make(chan struct{})
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/events/stream":
			w.Header().Set("Content-Type", "text/event-stream")
			w.(http.Flusher).Flush()
			select {
			case <-release:
			case <-r.Context().Done():
			}
		case "/api/executions/e":
			w.Header().Set("Content-Type", "application/json")
			_, _ = io.WriteString(w, `{"data":{"executionId":"e","status":"FAILURE","tasks":[`+
				`{"nodeId":"spotless","status":"SUCCESS"},{"nodeId":"tests","status":"FAILURE"},{"nodeId":"cli","status":"PENDING"}]}}`)
		}
	}))
	defer srv.Close()
	defer close(release)

	out, err := testutil.Run(t, WatchCmd(client.NewClient(srv.URL)), "--execution", "e", "--until-done")
	if err != nil && !feedEnded(err) {
		t.Fatal(err)
	}
	want := "NODE spotless SUCCESS\nNODE tests FAILURE\nWORKFLOW FAILURE\n"
	if out != want {
		t.Errorf("output %q, want %q", out, want)
	}
}

// The snapshot carries a node's metadata, so a late watch still shows why a node failed.
func TestWatchCmd_quietUntilDoneShowsTheReasonOfAMissedFailure(t *testing.T) {
	defer func(old time.Duration) { snapshotInterval = old }(snapshotInterval)
	snapshotInterval = 20 * time.Millisecond

	release := make(chan struct{})
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/events/stream":
			w.Header().Set("Content-Type", "text/event-stream")
			w.(http.Flusher).Flush()
			select {
			case <-release:
			case <-r.Context().Done():
			}
		case "/api/executions/e":
			w.Header().Set("Content-Type", "application/json")
			_, _ = io.WriteString(w, `{"data":{"executionId":"e","status":"FAILURE","tasks":[`+
				`{"nodeId":"tests","status":"FAILURE","metadata":{"error":"Process timed out after 300s"}}]}}`)
		}
	}))
	defer srv.Close()
	defer close(release)

	out, err := testutil.Run(t, WatchCmd(client.NewClient(srv.URL)), "--execution", "e", "--until-done", "--quiet")
	if err != nil && !feedEnded(err) {
		t.Fatal(err)
	}
	want := "NODE tests FAILURE\nERROR tests: Process timed out after 300s\nWORKFLOW FAILURE\n"
	if out != want {
		t.Errorf("output %q, want %q", out, want)
	}
}

func TestWatcher_dropsRepeatedNodeStatus(t *testing.T) {
	w := &watcher{}
	ev := client.ExecutionEvent{Type: client.EventNodeStatusChanged, NodeID: "n", Status: "SUCCESS"}
	out := testutil.Capture(t, func() { _ = w.handle(ev); _ = w.handle(ev) })
	if out != "NODE n SUCCESS\n" {
		t.Errorf("output %q", out)
	}
}

func TestWatcher_keepsSameNodeStatusOfDifferentExecutions(t *testing.T) {
	w := &watcher{}
	a := client.ExecutionEvent{Type: client.EventNodeStatusChanged, ExecutionID: "e1", NodeID: "n", Status: "FAILED"}
	b := a
	b.ExecutionID = "e2"
	out := testutil.Capture(t, func() { _ = w.handle(a); _ = w.handle(b) })
	if out != "NODE n FAILED\nNODE n FAILED\n" {
		t.Errorf("output %q", out)
	}
}

func TestWatchCmd_runsWithoutCommandContext(t *testing.T) {
	cmd := WatchCmd(testutil.Stream(t, watchStream))
	var err error
	out := testutil.Capture(t, func() { err = cmd.RunE(cmd, nil) })
	if (err != nil && !feedEnded(err)) || !strings.HasPrefix(out, "NODE spotless SUCCESS\n") {
		t.Errorf("out=%q err=%v", out, err)
	}
}

func TestWatcher_ignoresEventsAfterFinish(t *testing.T) {
	w := &watcher{finished: true}
	out := testutil.Capture(t, func() {
		_ = w.handle(client.ExecutionEvent{Type: client.EventLogLine, Line: "late"})
	})
	if out != "" {
		t.Errorf("printed %q after finish", out)
	}
}

func TestFormatEvent(t *testing.T) {
	yes := true
	for _, tt := range []struct {
		event client.ExecutionEvent
		want  string
	}{
		{client.ExecutionEvent{Type: client.EventControlOutcome, Command: "Stop", Applied: &yes}, "CONTROL Stop applied=true"},
		{client.ExecutionEvent{Type: client.EventControlOutcome, Command: "Stop"}, "CONTROL Stop applied=false"},
		{client.ExecutionEvent{Type: client.EventInputRequested}, "INPUT requested"},
		{inputEvent(client.EventInputRequested), "INPUT requested by ask (APPROVAL, HIGH risk)"},
		{client.ExecutionEvent{Type: client.EventInputEscalated}, "INPUT escalated"},
		{inputEvent(client.EventInputEscalated), "INPUT of ask escalated to ops"},
		{client.ExecutionEvent{Type: client.EventInputAnswered, Decision: "approve"}, "INPUT answered: approve"},
		{client.ExecutionEvent{Type: client.EventInputExpired}, "INPUT expired"},
		{client.ExecutionEvent{Type: client.EventInputCancelled, Reason: "run-ended"}, "INPUT cancelled: run-ended"},
		{client.ExecutionEvent{Type: "Other"}, "OTHER"},
	} {
		if got := formatEvent(tt.event); got != tt.want {
			t.Errorf("formatEvent(%+v) = %q, want %q", tt.event, got, tt.want)
		}
	}
}

// feedEnded: the mock feed closes after its events, which watch reports as an error.
func feedEnded(err error) bool { return strings.Contains(err.Error(), "ended unexpectedly") }

// inputEvent is an input event that carries a request snapshot, as the server sends it.
func inputEvent(kind string) client.ExecutionEvent {
	e := client.ExecutionEvent{Type: kind}
	e.Request = &struct {
		NodeID string `json:"nodeId"`
		Tier   string `json:"tier"`
		Spec   struct {
			Kind string `json:"kind"`
			Risk string `json:"risk"`
		} `json:"spec"`
	}{NodeID: "ask", Tier: "ops"}
	e.Request.Spec.Kind = "APPROVAL"
	e.Request.Spec.Risk = "HIGH"
	return e
}
