// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package approvals

import (
	"bytes"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"

	"com.infenia.yukta/go-cli/internal/client"
	"com.infenia.yukta/go-cli/internal/commands"
)

func run(t *testing.T, c *client.Client, format string, args ...string) (string, error) {
	orig := commands.GetOutputFormat()
	commands.SetTestOutputFormat(format)
	defer commands.SetTestOutputFormat(orig)

	r, w, _ := os.Pipe()
	old := os.Stdout
	os.Stdout = w
	cmd := ApprovalsCmd(c)
	cmd.SetArgs(args)
	cmd.SetOut(io.Discard)
	cmd.SetErr(io.Discard)
	err := cmd.Execute()
	w.Close()
	os.Stdout = old
	var buf bytes.Buffer
	_, _ = io.Copy(&buf, r)
	return buf.String(), err
}

func TestListAllSessions(t *testing.T) {
	var path string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		path = r.URL.Path
		_, _ = w.Write([]byte(`{"data":[{"sessionId":"s1","executionId":"e1","workflowId":"w","nodeId":"gate","name":"go","since":"t"}]}`))
	}))
	defer server.Close()

	out, err := run(t, client.NewClient(server.URL), "table", "list")
	if err != nil {
		t.Fatal(err)
	}
	if path != "/api/approvals" || !strings.Contains(out, "gate") || !strings.Contains(out, "s1") {
		t.Errorf("path %q, output %q", path, out)
	}
}

func TestListOneSessionFillsTheSessionAndPrintsJSON(t *testing.T) {
	var path string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		path = r.URL.Path
		_, _ = w.Write([]byte(`{"data":[{"executionId":"e1","workflowId":"w","nodeId":"gate","name":"go","since":"t"}]}`))
	}))
	defer server.Close()

	out, err := run(t, client.NewClient(server.URL), "json", "list", "--session", "s1")
	if err != nil {
		t.Fatal(err)
	}
	if path != "/api/sessions/s1/approvals" || !strings.Contains(out, `"sessionId": "s1"`) {
		t.Errorf("path %q, output %q", path, out)
	}
}

func TestListReportsAFailure(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Error(w, `{"message":"no"}`, http.StatusForbidden)
	}))
	defer server.Close()

	if _, err := run(t, client.NewClient(server.URL), "table", "list"); err == nil {
		t.Error("expected an error")
	}
}
