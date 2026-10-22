// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

// Package testutil holds helpers shared by command tests.
package testutil

import (
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"

	"com.infenia.yukta/go-cli/internal/client"
	"github.com/spf13/cobra"
)

// Server starts a test server that answers every request with status and body.
// It returns a client for it and a pointer to the requests seen so far, each as
// "METHOD /escaped/path?query".
func Server(t *testing.T, status int, body string) (*client.Client, *[]string) {
	t.Helper()
	var seen []string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		seen = append(seen, r.Method+" "+r.URL.RequestURI())
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(status)
		_, _ = io.WriteString(w, body)
	}))
	t.Cleanup(srv.Close)
	return client.NewClient(srv.URL), &seen
}

// Capture returns everything fn writes to os.Stdout.
func Capture(t *testing.T, fn func()) string {
	t.Helper()
	r, w, err := os.Pipe()
	if err != nil {
		t.Fatal(err)
	}
	old := os.Stdout
	os.Stdout = w
	defer func() { os.Stdout = old }()

	fn()
	_ = w.Close()
	out, _ := io.ReadAll(r)
	return string(out)
}

// Run executes cmd with args and returns what it printed to stdout plus the error.
// The output format is reset to "table" afterwards.
func Run(t *testing.T, cmd *cobra.Command, args ...string) (string, error) {
	t.Helper()
	cmd.SetArgs(args)
	cmd.SetOut(io.Discard)
	cmd.SetErr(io.Discard)
	var err error
	out := Capture(t, func() { err = cmd.Execute() })
	return out, err
}

// Stream starts a test server that answers every request with the given SSE body.
func Stream(t *testing.T, events string) *client.Client {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "text/event-stream")
		_, _ = io.WriteString(w, events)
	}))
	t.Cleanup(srv.Close)
	return client.NewClient(srv.URL)
}

// FinishedStream is Stream for an execution that has ended: after the events, the status check a
// followed stream makes reports SUCCESS, so the stream counts as complete.
func FinishedStream(t *testing.T, events string) *client.Client {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.HasPrefix(r.URL.Path, "/api/executions/") && !strings.HasSuffix(r.URL.Path, "/stream") {
			_, _ = io.WriteString(w, `{"data":{"status":"SUCCESS"}}`)
			return
		}
		w.Header().Set("Content-Type", "text/event-stream")
		_, _ = io.WriteString(w, events)
	}))
	t.Cleanup(srv.Close)
	return client.NewClient(srv.URL)
}
