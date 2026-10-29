// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package whoami

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

func run(t *testing.T, body string, status int, format string) (string, error) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/me" || r.Method != "GET" {
			t.Errorf("unexpected request %s %s", r.Method, r.URL.Path)
		}
		w.WriteHeader(status)
		_, _ = w.Write([]byte(body))
	}))
	defer server.Close()

	orig := commands.GetOutputFormat()
	commands.SetTestOutputFormat(format)
	defer commands.SetTestOutputFormat(orig)
	r, w, _ := os.Pipe()
	old := os.Stdout
	os.Stdout = w
	cmd := WhoamiCmd(client.NewClient(server.URL))
	err := cmd.RunE(cmd, nil)
	w.Close()
	os.Stdout = old
	var buf bytes.Buffer
	_, _ = io.Copy(&buf, r)
	return buf.String(), err
}

const scoped = `{"data":{"authenticated":true,"keyName":"alice","role":"OPERATOR","sessions":["a","b"],"authEnabled":true,"permissions":{"startRuns":true,"changeLimits":false}}}`
const open = `{"data":{"authenticated":false,"keyName":null,"role":"ADMIN","sessions":null,"authEnabled":false,"permissions":{"startRuns":true}}}`

func TestWhoamiTableShowsRoleSessionsAndPermissions(t *testing.T) {
	out, err := run(t, scoped, 200, "table")
	if err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{"alice", "OPERATOR", "a, b", "changeLimits", "false"} {
		if !strings.Contains(out, want) {
			t.Errorf("output lacks %q: %s", want, out)
		}
	}
}

func TestWhoamiWithNoKeyConfiguredShowsAllSessionsAndNoName(t *testing.T) {
	out, err := run(t, open, 200, "table")
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(out, "all") || !strings.Contains(out, "ADMIN") {
		t.Errorf("output = %s", out)
	}
}

func TestWhoamiJSONAndFailures(t *testing.T) {
	out, err := run(t, scoped, 200, "json")
	if err != nil || !strings.Contains(out, `"role": "OPERATOR"`) {
		t.Errorf("json output = %s, err = %v", out, err)
	}
	if _, err := run(t, `{"message":"no"}`, 401, "table"); err == nil {
		t.Error("expected an error on 401")
	}
	if _, err := run(t, "not json", 200, "table"); err == nil {
		t.Error("expected an error on a bad body")
	}
}
