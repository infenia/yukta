// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package digest

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
	t.Helper()
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/executions/e1/digest" {
			t.Errorf("unexpected path %s", r.URL.Path)
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
	cmd := DigestCmd(client.NewClient(server.URL))
	err := cmd.RunE(cmd, []string{"e1"})
	w.Close()
	os.Stdout = old
	var buf bytes.Buffer
	_, _ = io.Copy(&buf, r)
	return buf.String(), err
}

const failed = `{"data":{"executionId":"e1","workflowId":"wf","status":"FAILURE","complete":true,"runsSearched":2,
"failedNodes":[{"executionId":"e1","nodeId":"build","module":"PROCESS_EXECUTOR","status":"FAILURE","error":"exit 1","lines":9,"errorLines":3},
{"executionId":"e2","nodeId":"call","module":"HTTP","status":"TIMED_OUT","lines":1,"errorLines":0}],
"findings":[{"kind":"TEST","nodeId":"build","name":"T > m()","location":"T.java:3","message":"boom","line":7},
{"kind":"ERROR","nodeId":"call","message":"refused","line":1}],"findingsOmitted":4,
"next":[{"kind":"NODE_OUTPUT","executionId":"e1","nodeId":"build","offset":1,"why":"the lines around T > m()"},
{"kind":"NODE_OUTPUT","executionId":"e2","nodeId":"call","why":"x"},
{"kind":"SEARCH","executionId":"e2","nodeId":"call","level":"ERROR","why":"the error lines of the node"},
{"kind":"SEARCH","executionId":"e1","level":"ERROR","why":"the error lines of the run"}]}}`

func TestDigestPrintsFailuresAndNextCommands(t *testing.T) {
	out, err := run(t, failed, 200, "table")
	if err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{
		"run e1 (wf): FAILURE, ended, 2 run(s) searched",
		"failed: build [PROCESS_EXECUTOR] FAILURE: exit 1 (9 lines, 3 errors, run e1)",
		"failed: call [HTTP] TIMED_OUT (1 lines",
		"T > m()", "T.java:3", "build:7", "(4 more findings left out)",
		"yukta-cli logs <session-id> e1 --node build --offset 1 --limit 40",
		"yukta-cli logs <session-id> e2 --node call --offset 0 --limit 40",
		"yukta-cli logs search e2 --nodes call --level ERROR --mode LINES",
		"yukta-cli logs search e1 --level ERROR --mode LINES",
	} {
		if !strings.Contains(out, want) {
			t.Errorf("output lacks %q:\n%s", want, out)
		}
	}
}

func TestDigestOfARunningRunWithoutFindings(t *testing.T) {
	out, err := run(t, `{"data":{"executionId":"e1","status":"RUNNING","complete":false}}`, 200, "table")
	if err != nil || !strings.Contains(out, "still running") || strings.Contains(out, "Kind") {
		t.Errorf("err=%v out=%s", err, out)
	}
}

func TestDigestJSONAndErrors(t *testing.T) {
	out, err := run(t, failed, 200, "json")
	if err != nil || !strings.Contains(out, `"findingsOmitted": 4`) {
		t.Errorf("err=%v out=%s", err, out)
	}
	if _, err := run(t, `{"message":"no"}`, 404, "table"); err == nil {
		t.Error("expected an error for an unknown run")
	}
}
