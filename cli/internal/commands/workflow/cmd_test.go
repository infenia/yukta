// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package workflow

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"com.infenia.yukta/go-cli/internal/client"
	"com.infenia.yukta/go-cli/internal/commands"
	"com.infenia.yukta/go-cli/internal/testutil"
)

const execOK = `{"data":{"executionId":"exec-1"}}`

func TestExecutionCommands(t *testing.T) {
	tests := []struct {
		name    string
		body    string
		args    []string
		wantReq string
		wantOut string
	}{
		{"pause", execOK, []string{"pause", "s", "e"}, "POST /api/executions/e/pause", "Execution paused: exec-1"},
		{"resume", execOK, []string{"resume", "s", "e"}, "POST /api/executions/e/resume", "Execution resumed: exec-1"},
		{"stop", `{"data":{"executionIds":["a","b"]}}`, []string{"stop", "s", "w"}, "POST /api/workflow/s/w/stop", "Stopped 2 execution(s)\n  - a\n  - b"},
		{"status", `{"data":{"executionId":"exec-1","workflowId":"w","status":"RUNNING","tasks":[{}]}}`,
			[]string{"status", "s", "e"}, "GET /api/executions/e", "Tasks:    1"},
		{"history", `{"data":[{"executionId":"exec-1","workflowId":"w","status":"DONE"}]}`,
			[]string{"history", "s"}, "GET /api/workflow/s/history", "Total Executions: 1"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			c, seen := testutil.Server(t, 200, tt.body)
			out, err := testutil.Run(t, WorkflowCmd(c), tt.args...)
			if err != nil {
				t.Fatal(err)
			}
			if len(*seen) != 1 || (*seen)[0] != tt.wantReq {
				t.Errorf("requests %v, want %q", *seen, tt.wantReq)
			}
			if !strings.Contains(out, tt.wantOut) {
				t.Errorf("output %q does not contain %q", out, tt.wantOut)
			}
		})
	}
}

func TestExecutionCommands_jsonOutput(t *testing.T) {
	commands.SetTestOutputFormat("json")
	defer commands.SetTestOutputFormat("table")

	for _, args := range [][]string{{"status", "s", "e"}, {"history", "s"}, {"start", "s", "w"}} {
		c, _ := testutil.Server(t, 200, `{"data":{"executionId":"exec-1"}}`)
		if args[0] == "history" {
			c, _ = testutil.Server(t, 200, `{"data":[{"executionId":"exec-1"}]}`)
		}
		out, err := testutil.Run(t, WorkflowCmd(c), args...)
		if err != nil || !strings.Contains(out, "exec-1") {
			t.Errorf("%v: out=%q err=%v", args, out, err)
		}
	}
}

func TestWorkflowCommands_errors(t *testing.T) {
	c, _ := testutil.Server(t, 500, "boom")
	for _, args := range [][]string{
		{"start", "s", "w"}, {"stop", "s", "w"}, {"pause", "s", "e"}, {"resume", "s", "e"},
		{"status", "s", "e"}, {"history", "s"},
	} {
		if _, err := testutil.Run(t, WorkflowCmd(c), args...); err == nil || !strings.Contains(err.Error(), "500") {
			t.Errorf("%v: expected API error, got %v", args, err)
		}
	}
}

func startBody(t *testing.T, args ...string) (map[string]any, string, error) {
	t.Helper()
	var got map[string]any
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewDecoder(r.Body).Decode(&got)
		_, _ = io.WriteString(w, execOK)
	}))
	defer srv.Close()
	out, err := testutil.Run(t, WorkflowCmd(client.NewClient(srv.URL)), append([]string{"start", "s", "w"}, args...)...)
	return got, out, err
}

func TestStartCmd(t *testing.T) {
	got, out, err := startBody(t)
	if err != nil || !strings.Contains(out, "Workflow started with execution ID: exec-1") {
		t.Fatalf("out=%q err=%v", out, err)
	}
	if _, has := got["payload"]; has || got["sessionId"] != "s" || got["workflowId"] != "w" {
		t.Errorf("unexpected body %v", got)
	}

	got, _, err = startBody(t, "--payload", `{"k":"v"}`)
	if err != nil || got["payload"].(map[string]any)["k"] != "v" {
		t.Errorf("inline payload: %v %v", got, err)
	}

	file := filepath.Join(t.TempDir(), "p.json")
	if err := os.WriteFile(file, []byte(`{"a":1}`), 0o600); err != nil {
		t.Fatal(err)
	}
	got, _, err = startBody(t, "--payload-file", file)
	if err != nil || got["payload"].(map[string]any)["a"] != float64(1) {
		t.Errorf("file payload: %v %v", got, err)
	}
}

func TestStartCmd_invalidPayload(t *testing.T) {
	missing := filepath.Join(t.TempDir(), "nope.json")
	for name, args := range map[string][]string{
		"not an object": {"--payload", "[1]"},
		"missing file":  {"--payload-file", missing},
		"both flags":    {"--payload", "{}", "--payload-file", missing},
	} {
		if _, _, err := startBody(t, args...); err == nil {
			t.Errorf("%s: expected error", name)
		}
	}
}

const progress = "data: {\"executionId\":\"exec-1\",\"status\":\"COMPLETED\"}\n\n"

func TestStatusStreamCmd(t *testing.T) {
	var query string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.HasPrefix(r.URL.Path, "/api/executions/") && !strings.HasSuffix(r.URL.Path, "/stream") {
			_, _ = io.WriteString(w, `{"data":{"status":"SUCCESS"}}`) // the execution has ended
			return
		}
		query = r.URL.RawQuery
		w.Header().Set("Content-Type", "text/event-stream")
		_, _ = io.WriteString(w, progress)
	}))
	defer srv.Close()

	out, err := testutil.Run(t, WorkflowCmd(client.NewClient(srv.URL)), "status-stream", "s", "e", "--include-history")
	if err != nil || !strings.Contains(out, "COMPLETED") {
		t.Fatalf("out=%q err=%v", out, err)
	}
	if query != "includeHistory=true" {
		t.Errorf("query %q", query)
	}
}

func TestStatusStreamCmd_errorsAndCancel(t *testing.T) {
	c, _ := testutil.Server(t, 500, "boom")
	if _, err := testutil.Run(t, WorkflowCmd(c), "status-stream", "s", "e"); err == nil {
		t.Error("expected API error")
	}

	// A cancelled context (Ctrl-C) is a clean exit.
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	cmd := WorkflowCmd(testutil.FinishedStream(t, progress))
	cmd.SetArgs([]string{"status-stream", "s", "e"})
	if err := cmd.ExecuteContext(ctx); err != nil {
		t.Errorf("expected nil on cancel, got %v", err)
	}
}

func TestStatusStreamCmd_withoutCobraContext(t *testing.T) {
	cmd := StatusStreamCmd(testutil.FinishedStream(t, progress))
	var err error
	out := testutil.Capture(t, func() { err = cmd.RunE(cmd, []string{"s", "e"}) })
	if err != nil || !strings.Contains(out, "COMPLETED") {
		t.Errorf("out=%q err=%v", out, err)
	}
}
