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

func TestExitCode_refusedBySupervisor(t *testing.T) {
	bodies := map[string]string{
		"owner":    `{"message":"guard controls this. People cannot do this directly.","errors":[{"field":"code","message":"CONTROLLED_BY_SUPERVISOR"},{"field":"supervisor","message":"guard"}]}`,
		"rejected": `{"message":"guard: closed","errors":[{"field":"code","message":"CONTROL_REJECTED"}]}`,
	}
	for name, body := range bodies {
		status := 409
		if name == "rejected" {
			status = 403
		}
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
			http.Error(w, body, status)
		}))
		_, err := client.NewClient(srv.URL).GetSessionDetails("s")
		srv.Close()
		if got := ExitCode(err, false); got != ExitRefused {
			t.Errorf("%s: exit %d, want %d (%v)", name, got, ExitRefused, err)
		}
		if !client.IsRefusal(err) {
			t.Errorf("%s: not a refusal: %v", name, err)
		}
	}
	// a plain 409 (no supervisor code) and a non-API error are not refusals
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		http.Error(w, `{"message":"busy"}`, 409)
	}))
	_, err := client.NewClient(srv.URL).GetSessionDetails("s")
	srv.Close()
	if client.IsRefusal(err) || ExitCode(err, false) != ExitFailure {
		t.Errorf("plain conflict taken for a refusal: %v", err)
	}
	if client.IsRefusal(context.Canceled) {
		t.Error("a non-API error is not a refusal")
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
