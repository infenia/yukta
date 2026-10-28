// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package logs

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"com.infenia.yukta/go-cli/internal/client"
	"com.infenia.yukta/go-cli/internal/testutil"
)

func TestLogsCmd_usage(t *testing.T) {
	cmd := LogsCmd(client.NewClient(""))
	if !strings.HasPrefix(cmd.Use, "logs") {
		t.Errorf("unexpected Use %q", cmd.Use)
	}
	if _, err := testutil.Run(t, cmd, "only-one-arg"); err == nil {
		t.Error("expected arg-count error")
	}
}

func TestLogsCmd_streams(t *testing.T) {
	tests := []struct {
		name     string
		args     []string
		wantPath string
		wantOut  string
	}{
		{"lines", []string{"s", "e"}, "/api/sessions/s/executions/e/logs", "line 1\nline 2\n"},
		{"entries", []string{"s", "e", "--entries"}, "/api/sessions/s/executions/e/logs/entries", "line 1\nline 2\n"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			var path string
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if strings.HasPrefix(r.URL.Path, "/api/executions/") {
					_, _ = io.WriteString(w, `{"data":{"status":"SUCCESS"}}`)
					return
				}
				path = r.URL.Path
				w.Header().Set("Content-Type", "text/event-stream")
				_, _ = io.WriteString(w, "data: line 1\n\ndata: line 2\n\n")
			}))
			defer srv.Close()

			out, err := testutil.Run(t, LogsCmd(client.NewClient(srv.URL)), tt.args...)
			if err != nil || out != tt.wantOut {
				t.Errorf("out=%q err=%v", out, err)
			}
			if path != tt.wantPath {
				t.Errorf("path %q, want %q", path, tt.wantPath)
			}
		})
	}
}

func TestLogsCmd_apiError(t *testing.T) {
	c, _ := testutil.Server(t, 500, "boom")
	if _, err := testutil.Run(t, LogsCmd(c), "s", "e"); err == nil {
		t.Error("expected API error")
	}
}

func TestLogsCmd_cancelIsCleanExit(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	cmd := LogsCmd(testutil.FinishedStream(t, "data: x\n\n"))
	cmd.SetArgs([]string{"s", "e"})
	if err := cmd.ExecuteContext(ctx); err != nil {
		t.Errorf("expected nil on Ctrl-C, got %v", err)
	}
}

func TestLogsCmd_withoutCobraContext(t *testing.T) {
	cmd := LogsCmd(testutil.FinishedStream(t, "data: x\n\n"))
	var err error
	out := testutil.Capture(t, func() { err = cmd.RunE(cmd, []string{"s", "e"}) })
	if err != nil || out != "x\n" {
		t.Errorf("out=%q err=%v", out, err)
	}
}

func TestLogsCmd_node_readsASliceOfTheNodeOutput(t *testing.T) {
	var gotPath, gotQuery string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath, gotQuery = r.URL.Path, r.URL.RawQuery
		_, _ = io.WriteString(w, `{"data":{"nodeId":"run","stream":"all","totalLines":10000,"totalBytes":99,"matchedLines":2,"truncated":true,"nextOffset":2,"lines":[{"number":5,"text":"boom"},{"number":9,"text":"bang"}]}}`)
	}))
	defer srv.Close()

	out, err := testutil.Run(t, LogsCmd(client.NewClient(srv.URL)), "s", "e", "--node", "run", "--grep", "b", "--context", "1", "--tail", "2")
	if err != nil {
		t.Fatalf("err=%v", err)
	}
	if out != "5: boom\n9: bang\n" {
		t.Errorf("out=%q", out)
	}
	if gotPath != "/api/executions/e/nodes/run/output" {
		t.Errorf("path %q", gotPath)
	}
	for _, want := range []string{"grep=b", "context=1", "tail=2"} {
		if !strings.Contains(gotQuery, want) {
			t.Errorf("query %q lacks %q", gotQuery, want)
		}
	}
	if strings.Contains(gotQuery, "head=") || strings.Contains(gotQuery, "offset=") {
		t.Errorf("unset flags were sent: %q", gotQuery)
	}
}

func TestLogsCmd_node_serverErrorIsReported(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Error(w, `{"message":"Node not found"}`, http.StatusNotFound)
	}))
	defer srv.Close()

	if _, err := testutil.Run(t, LogsCmd(client.NewClient(srv.URL)), "s", "e", "--node", "nope"); err == nil {
		t.Error("expected the 404 to be an error")
	}
}
