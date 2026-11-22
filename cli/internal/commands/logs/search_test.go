// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package logs

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

// runSearch runs "logs search e1 <flags>" against a fake server and returns stdout, stderr and the
// query it sent.
func runSearch(t *testing.T, body string, status int, format string, flags ...string) (string, string, string, error) {
	t.Helper()
	var query string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/executions/e1/logs/search" {
			t.Errorf("unexpected path %s", r.URL.Path)
		}
		query = r.URL.RawQuery
		w.WriteHeader(status)
		_, _ = w.Write([]byte(body))
	}))
	defer server.Close()

	orig := commands.GetOutputFormat()
	commands.SetTestOutputFormat(format)
	defer commands.SetTestOutputFormat(orig)
	outR, outW, _ := os.Pipe()
	errR, errW, _ := os.Pipe()
	oldOut, oldErr := os.Stdout, os.Stderr
	os.Stdout, os.Stderr = outW, errW
	cmd := SearchCmd(client.NewClient(server.URL))
	if err := cmd.ParseFlags(flags); err != nil {
		t.Fatal(err)
	}
	err := cmd.RunE(cmd, []string{"e1"})
	outW.Close()
	errW.Close()
	os.Stdout, os.Stderr = oldOut, oldErr
	var stdout, stderr bytes.Buffer
	_, _ = io.Copy(&stdout, outR)
	_, _ = io.Copy(&stderr, errR)
	return stdout.String(), stderr.String(), query, err
}

func TestSearchCountsPerNode(t *testing.T) {
	body := `{"data":{"executionId":"e1","mode":"COUNT","executionsScanned":1,"linesScanned":12,"matches":12,
"nodes":[{"executionId":"e1","nodeId":"build","lines":10,"errors":2,"warnings":1,"matches":10}],"nodesOmitted":3,
"hint":"Read the lines with mode=LINES and nodes=build, level=ERROR."}}`
	out, errOut, query, err := runSearch(t, body, 200, "table", "--children", "--level", "ERROR")
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(out, "build") || !strings.Contains(out, "10") {
		t.Errorf("table = %s", out)
	}
	for _, want := range []string{"12 matches in 12 lines of 1 run(s)", "(3 more nodes left out)", "hint: Read the lines"} {
		if !strings.Contains(errOut, want) {
			t.Errorf("stderr lacks %q: %s", want, errOut)
		}
	}
	if !strings.Contains(query, "includeChildren=true") || !strings.Contains(query, "level=ERROR") {
		t.Errorf("query = %s", query)
	}
}

func TestSearchLinesWithCursor(t *testing.T) {
	body := `{"data":{"mode":"LINES","matches":2,"lines":[{"nodeId":"build","sequence":4,"level":"INFO","match":false,"text":"before"},
{"nodeId":"build","sequence":5,"level":"ERROR","match":true,"text":"FAILED"}],"truncated":true,"nextCursor":"e1:5"}}`
	out, errOut, _, err := runSearch(t, body, 200, "table", "--mode", "LINES")
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(out, "  build#4 [INFO] before") || !strings.Contains(out, "> build#5 [ERROR] FAILED") {
		t.Errorf("lines = %s", out)
	}
	if !strings.Contains(errOut, "next page: --cursor e1:5") {
		t.Errorf("stderr = %s", errOut)
	}
}

func TestSearchJSONAndErrors(t *testing.T) {
	out, _, _, err := runSearch(t, `{"data":{"mode":"COUNT","matches":1}}`, 200, "json")
	if err != nil || !strings.Contains(out, `"matches": 1`) {
		t.Errorf("err=%v out=%s", err, out)
	}
	if _, _, _, err := runSearch(t, `{"message":"bad"}`, 400, "table"); err == nil {
		t.Error("expected an error for a bad search")
	}
}
