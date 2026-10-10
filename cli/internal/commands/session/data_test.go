// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package session

import (
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"

	"com.infenia.yukta/go-cli/internal/client"
)

func TestDataCommandsHitExpectedPaths(t *testing.T) {
	var path string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		path = r.URL.EscapedPath()
		_, _ = w.Write([]byte(`{"data":{"ok":true}}`))
	}))
	defer srv.Close()
	c := client.NewClient(srv.URL)

	tests := []struct {
		args []string
		want string
	}{
		{[]string{"summaries"}, "/api/sessions/summaries"},
		{[]string{"workflow", "graph", "s1", "w/1"}, "/api/sessions/s1/workflows/w%2F1/graph"},
		{[]string{"workflow", "executions", "s1", "w1"}, "/api/sessions/s1/workflows/w1/executions"},
	}
	for _, tt := range tests {
		path = ""
		cmd := SessionCmd(c)
		cmd.SetArgs(tt.args)
		if err := cmd.Execute(); err != nil {
			t.Fatalf("%v: %v", tt.args, err)
		}
		if path != tt.want {
			t.Errorf("%v: got path %q, want %q", tt.args, path, tt.want)
		}
	}
}

func TestDataCmd_propagatesError(t *testing.T) {
	cmd := dataCmd("x", "x", 0, func([]string) (any, error) { return nil, errors.New("boom") })
	if err := cmd.RunE(cmd, nil); err == nil {
		t.Fatal("expected error")
	}
}
