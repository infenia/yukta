// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package controlbus

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"com.infenia.yukta/go-cli/internal/client"
	"com.infenia.yukta/go-cli/internal/testutil"
)

func sse(event string) string { return "data: " + event + "\n\n" }

// A parent "e" that starts a child "c" (workflow "check"): the child's lines are named after its
// workflow, and the child ending does not end an --until-done watch; the parent ending does.
const treeStream = `{"type":"WorkflowStatusChanged","event":{"executionId":"c","workflowId":"check","status":"RUNNING"}}`

func treeEvents() string {
	return sse(treeStream) +
		sse(`{"type":"NodeStatusChanged","event":{"executionId":"c","nodeId":"style","status":"FAILURE","metadata":{"error":"checkstyle"}}}`) +
		sse(`{"type":"LogLine","event":{"executionId":"x9876543210","nodeId":"run","line":"hello"}}`) +
		sse(`{"type":"WorkflowStatusChanged","event":{"executionId":"c","workflowId":"check","status":"FAILURE"}}`) +
		sse(`{"type":"NodeStatusChanged","event":{"executionId":"e","nodeId":"sub","status":"SUCCESS"}}`) +
		sse(`{"type":"WorkflowStatusChanged","event":{"executionId":"e","status":"SUCCESS"}}`) +
		sse(`{"type":"LogLine","event":{"executionId":"e","nodeId":"late","line":"after the end"}}`)
}

// treeServer serves events for executions, history and status; it records the stream queries.
type treeServer struct {
	mu      sync.Mutex
	queries []string
	streams map[string]string // events per watched execution id
	status  map[string]string // terminal status per execution, for the --until-done poll
	history string            // JSON items of the session history
}

func (s *treeServer) handler(t *testing.T) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.URL.Path == "/api/events/stream":
			s.mu.Lock()
			s.queries = append(s.queries, r.URL.RawQuery)
			s.mu.Unlock()
			w.Header().Set("Content-Type", "text/event-stream")
			_, _ = io.WriteString(w, s.streams[r.URL.Query().Get("executionId")])
			w.(http.Flusher).Flush()
			<-r.Context().Done()
		case strings.HasPrefix(r.URL.Path, "/api/executions/"):
			id := strings.TrimPrefix(r.URL.Path, "/api/executions/")
			status := s.status[id]
			if status == "" {
				status = "RUNNING"
			}
			w.Header().Set("Content-Type", "application/json")
			_, _ = io.WriteString(w, `{"data":{"executionId":"`+id+`","sessionId":"s","workflowId":"w","status":"`+status+`"}}`)
		case r.URL.Path == "/api/workflow/s/history":
			w.Header().Set("Content-Type", "application/json")
			_, _ = io.WriteString(w, `{"data":[`+s.history+`]}`)
		default:
			t.Errorf("unexpected path %s", r.URL.Path)
		}
	})
}

func runTree(t *testing.T, s *treeServer, args ...string) (string, error) {
	t.Helper()
	srv := httptest.NewServer(s.handler(t))
	defer srv.Close()
	done := make(chan struct{})
	var out string
	var err error
	go func() {
		out, err = testutil.Run(t, WatchCmd(client.NewClient(srv.URL)), args...)
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(10 * time.Second):
		t.Fatal("watch did not end")
	}
	return out, err
}

func TestWatchCmd_treeNamesChildLines_andEndsWithTheRootNotTheChild(t *testing.T) {
	s := &treeServer{streams: map[string]string{"e": treeEvents()}}
	out, err := runTree(t, s, "--execution", "e", "--until-done")
	if err != nil {
		t.Fatal(err)
	}
	want := "WORKFLOW check RUNNING\nNODE check/style FAILURE\nLOG x9876543/run: hello\nWORKFLOW check FAILURE\n" +
		"NODE sub SUCCESS\nWORKFLOW SUCCESS\n"
	if out != want {
		t.Errorf("output:\n%q\nwant:\n%q", out, want)
	}
	if len(s.queries) != 1 || !strings.Contains(s.queries[0], "tree=true") {
		t.Errorf("stream queries %v, want one with tree=true", s.queries)
	}
}

func TestWatchCmd_quietTree_showsChildProblemsButNotChildrenThatWentWell(t *testing.T) {
	events := sse(treeStream) +
		sse(`{"type":"WorkflowStatusChanged","event":{"executionId":"c","workflowId":"check","status":"SUCCESS"}}`) +
		sse(`{"type":"NodeStatusChanged","event":{"executionId":"c","nodeId":"style","status":"FAILURE","metadata":{"error":"bad import"}}}`) +
		sse(`{"type":"WorkflowStatusChanged","event":{"executionId":"e","status":"FAILURE"}}`)
	s := &treeServer{streams: map[string]string{"e": events}}
	out, err := runTree(t, s, "--execution", "e", "--until-done", "--quiet")
	if err != nil {
		t.Fatal(err)
	}
	want := "NODE check/style FAILURE\nERROR check/style: bad import\nWORKFLOW FAILURE\n"
	if out != want {
		t.Errorf("output:\n%q\nwant:\n%q", out, want)
	}
}

func TestWatchCmd_treeFalse_asksForTheExecutionOnly(t *testing.T) {
	s := &treeServer{streams: map[string]string{"e": sse(`{"type":"WorkflowStatusChanged","event":{"executionId":"e","status":"SUCCESS"}}`)}}
	if _, err := runTree(t, s, "--execution", "e", "--until-done", "--tree=false"); err != nil {
		t.Fatal(err)
	}
	if len(s.queries) != 1 || strings.Contains(s.queries[0], "tree") {
		t.Errorf("stream queries %v, want one without tree", s.queries)
	}
}

func TestWatchCmd_followRestarts_movesOnToTheRunThatRestartedTheExecution(t *testing.T) {
	defer func(w, p time.Duration) { successorWait, successorPoll = w, p }(successorWait, successorPoll)
	successorWait, successorPoll = time.Second, 10*time.Millisecond
	s := &treeServer{
		streams: map[string]string{
			"e":  sse(`{"type":"WorkflowStatusChanged","event":{"executionId":"e","status":"SUCCESS"}}`),
			"e2": sse(`{"type":"WorkflowStatusChanged","event":{"executionId":"e2","status":"FAILURE"}}`),
		},
		history: `{"executionId":"e2","workflowId":"w","status":"FAILURE","restartOf":"e"},` +
			`{"executionId":"e","workflowId":"w","status":"SUCCESS"}`,
	}
	out, err := runTree(t, s, "--execution", "e", "--until-done", "--follow-restarts")
	if err != nil {
		t.Fatal(err)
	}
	want := "WORKFLOW SUCCESS\nRESTARTED e e2\nWORKFLOW FAILURE\n"
	if out != want {
		t.Errorf("output:\n%q\nwant:\n%q", out, want)
	}
}

func TestWatchCmd_followRestarts_endsWhenNoRunRestartsTheExecution(t *testing.T) {
	defer func(w, p time.Duration) { successorWait, successorPoll = w, p }(successorWait, successorPoll)
	successorWait, successorPoll = 50*time.Millisecond, 10*time.Millisecond
	s := &treeServer{
		streams: map[string]string{"e": sse(`{"type":"WorkflowStatusChanged","event":{"executionId":"e","status":"SUCCESS"}}`)},
		history: `{"executionId":"e","workflowId":"w","status":"SUCCESS"}`,
	}
	out, err := runTree(t, s, "--execution", "e", "--until-done", "--follow-restarts")
	if err != nil {
		t.Fatal(err)
	}
	if out != "WORKFLOW SUCCESS\n" {
		t.Errorf("output %q", out)
	}
}

func TestSuccessorOf_unknownExecution_hasNone(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		http.Error(w, `{"message":"Execution not found"}`, http.StatusNotFound)
	}))
	defer srv.Close()
	if next := successorOf(t.Context(), client.NewClient(srv.URL), "nope"); next != "" {
		t.Errorf("successor %q, want none", next)
	}
}

func TestWatchCmd_rejectsAFlagAsTheExecutionId_andFollowRestartsWithoutUntilDone(t *testing.T) {
	cases := map[string][]string{
		"--execution needs an execution id":    {"--execution", "--until-done"},
		"--follow-restarts needs --until-done": {"--execution", "e", "--follow-restarts"},
	}
	for want, args := range cases {
		_, err := testutil.Run(t, WatchCmd(client.NewClient("http://127.0.0.1:1")), args...)
		if err == nil || !strings.Contains(err.Error(), want) {
			t.Errorf("%v: error %v, want %q", args, err, want)
		}
	}
}

func TestSuccessorOf_stopsLookingWhenTheWatchIsCancelled(t *testing.T) {
	defer func(w, p time.Duration) { successorWait, successorPoll = w, p }(successorWait, successorPoll)
	successorWait, successorPoll = time.Minute, time.Minute
	s := &treeServer{history: `{"executionId":"e","workflowId":"w","status":"SUCCESS"}`}
	srv := httptest.NewServer(s.handler(t))
	defer srv.Close()
	ctx, cancel := context.WithCancel(t.Context())
	cancel()
	if next := successorOf(ctx, client.NewClient(srv.URL), "e"); next != "" {
		t.Errorf("successor %q, want none", next)
	}
}
