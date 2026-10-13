// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package result

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

func TestResultCmd_usage(t *testing.T) {
	cmd := ResultCmd(client.NewClient(""))
	if !strings.HasPrefix(cmd.Use, "result") {
		t.Errorf("unexpected Use %q", cmd.Use)
	}
	if _, err := testutil.Run(t, cmd, "only-one-arg"); err == nil {
		t.Error("expected arg-count error")
	}
}

func TestResultCmd_streamsEventsAndPassesTheNodeFilter(t *testing.T) {
	var path, query string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		path, query = r.URL.Path, r.URL.RawQuery
		w.Header().Set("Content-Type", "text/event-stream")
		_, _ = io.WriteString(w,
			"data: {\"kind\":\"RESULT\"}\n\n"+
				"data: {\"kind\":\"EXECUTION_END\"}\n\n")
	}))
	defer srv.Close()

	out, err := testutil.Run(t, ResultCmd(client.NewClient(srv.URL)), "s", "e", "--node", "t 1")

	want := "{\"kind\":\"RESULT\"}\n{\"kind\":\"EXECUTION_END\"}\n"
	if err != nil || out != want {
		t.Errorf("out=%q err=%v", out, err)
	}
	if path != "/api/sessions/s/executions/e/results" || query != "nodeId=t+1" {
		t.Errorf("path %q query %q", path, query)
	}
}

func TestResultCmd_apiError(t *testing.T) {
	c, _ := testutil.Server(t, 404, "nope")
	if _, err := testutil.Run(t, ResultCmd(c), "s", "e"); err == nil {
		t.Error("expected API error")
	}
}

func TestResultCmd_cancelIsCleanExit(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	cmd := ResultCmd(testutil.Stream(t, "data: x\n\n"))
	cmd.SetArgs([]string{"s", "e"})
	if err := cmd.ExecuteContext(ctx); err != nil {
		t.Errorf("expected nil on Ctrl-C, got %v", err)
	}
}

func TestResultCmd_withoutCobraContext(t *testing.T) {
	cmd := ResultCmd(testutil.Stream(t, "data: x\n\n"))
	var err error
	out := testutil.Capture(t, func() { err = cmd.RunE(cmd, []string{"s", "e"}) })
	if err != nil || out != "x\n" {
		t.Errorf("out=%q err=%v", out, err)
	}
}
