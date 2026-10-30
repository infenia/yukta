// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package commands

import (
	"com.infenia.yukta/go-cli/internal/client"
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestReadJSONArg(t *testing.T) {
	dir := t.TempDir()
	file := filepath.Join(dir, "c.json")
	if err := os.WriteFile(file, []byte(`{"a":1}`), 0o600); err != nil {
		t.Fatal(err)
	}
	unreadable := filepath.Join(dir, "u.json")
	if err := os.WriteFile(unreadable, []byte(`{}`), 0o000); err != nil {
		t.Fatal(err)
	}
	cases := []struct {
		name, in string
		want     string
		wantErr  bool
	}{
		{"file", file, `{"a":1}`, false},
		{"inline object", ` {"a":2}`, ` {"a":2}`, false},
		{"inline array", `[1]`, `[1]`, false},
		{"missing file", filepath.Join(dir, "typo.json"), "", true},
		{"directory", dir, "", true},
		{"unreadable", unreadable, "", true},
	}
	for _, tc := range cases {
		got, err := ReadJSONArg(tc.in)
		if (err != nil) != tc.wantErr || string(got) != tc.want {
			t.Errorf("%s: got %q, %v", tc.name, got, err)
		}
	}
}

func TestExitCode(t *testing.T) {
	if got := ExitCode(nil, false); got != 0 {
		t.Errorf("nil: %d", got)
	}
	if got := ExitCode(errors.New("x"), true); got != ExitUsage {
		t.Errorf("usage: %d", got)
	}
	if got := ExitCode(errors.New("x"), false); got != ExitFailure {
		t.Errorf("failure: %d", got)
	}
	if got := ExitCode(context.DeadlineExceeded, false); got != ExitTimeout {
		t.Errorf("timeout: %d", got)
	}
}

func TestExitCode_apiStatus(t *testing.T) {
	for status, want := range map[int]int{404: ExitNotFound, 500: ExitServer, 504: ExitTimeout, 400: ExitFailure} {
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
			http.Error(w, `{"message":"boom"}`, status)
		}))
		_, err := client.NewClient(srv.URL).GetSessionDetails("s")
		srv.Close()
		if got := ExitCode(err, false); got != want {
			t.Errorf("status %d: exit %d, want %d (%v)", status, got, want, err)
		}
		if !strings.Contains(err.Error(), ": boom") || strings.Contains(err.Error(), "{") {
			t.Errorf("status %d: envelope not unwrapped: %v", status, err)
		}
	}
}

func TestRootCmd_rejectsUnknownOutput(t *testing.T) {
	defer SetTestOutputFormat("table")
	cmd := RootCmd(client.NewClient(""))
	cmd.SetArgs([]string{"-o", "yaml", "version"})
	if err := cmd.Execute(); err == nil || !strings.Contains(err.Error(), "invalid --output") {
		t.Errorf("got %v", err)
	}
}
