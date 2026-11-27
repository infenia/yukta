// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package workflow

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"com.infenia.yukta/go-cli/internal/client"
	"com.infenia.yukta/go-cli/internal/commands"
	"com.infenia.yukta/go-cli/internal/testutil"
)

const passedReport = `{"data":{"passed":true,"status":"SUCCESS","assertions":[{"name":"status","passed":true}]}}`

const failedReport = `{"data":{"passed":false,"status":"SUCCESS","assertions":[` +
	`{"name":"status","passed":true},{"name":"reached notify","passed":false,"detail":"not reached"}]}}`

// testFile writes a test file and returns its path.
func testFile(t *testing.T, content string) string {
	t.Helper()
	file := filepath.Join(t.TempDir(), "deploy.test.json")
	if err := os.WriteFile(file, []byte(content), 0o600); err != nil {
		t.Fatal(err)
	}
	return file
}

func TestTestCmd_sendsTheSpecWithoutTheWorkflowId_andPrintsEachAssertion(t *testing.T) {
	var path string
	var got map[string]any
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		path = r.Method + " " + r.URL.Path
		_ = json.NewDecoder(r.Body).Decode(&got)
		_, _ = io.WriteString(w, passedReport)
	}))
	defer srv.Close()
	file := testFile(t, `{"workflowId":"deploy","input":{"env":"prod"},"expect":{"status":"SUCCESS"}}`)

	out, err := testutil.Run(t, WorkflowCmd(client.NewClient(srv.URL)), "test", "s", file)

	if err != nil {
		t.Fatal(err)
	}
	if path != "POST /api/sessions/s/workflows/deploy/test" {
		t.Errorf("request %q", path)
	}
	if _, has := got["workflowId"]; has || got["input"].(map[string]any)["env"] != "prod" {
		t.Errorf("body %v", got)
	}
	if !strings.Contains(out, "PASS  status") || !strings.Contains(out, "PASSED deploy (run SUCCESS, 0 of 1") {
		t.Errorf("output %q", out)
	}
}

func TestTestCmd_failingAssertion_printsItsDetail_andFails(t *testing.T) {
	c, _ := testutil.Server(t, 200, failedReport)

	out, err := testutil.Run(t, WorkflowCmd(c), "test", "s", testFile(t, `{"workflowId":"deploy"}`))

	if err == nil || !strings.Contains(err.Error(), "workflow test of deploy failed") {
		t.Errorf("err %v", err)
	}
	if !strings.Contains(out, "FAIL  reached notify: not reached") || !strings.Contains(out, "FAILED deploy") {
		t.Errorf("output %q", out)
	}
}

func TestTestCmd_jsonOutput(t *testing.T) {
	commands.SetTestOutputFormat("json")
	defer commands.SetTestOutputFormat("table")
	c, _ := testutil.Server(t, 200, passedReport)

	out, err := testutil.Run(t, WorkflowCmd(c), "test", "s", testFile(t, `{"workflowId":"deploy"}`))

	if err != nil || !strings.Contains(out, `"passed": true`) {
		t.Errorf("out=%q err=%v", out, err)
	}
}

func TestTestCmd_badFiles_andServerErrors(t *testing.T) {
	c, _ := testutil.Server(t, 500, "boom")
	for name, args := range map[string][]string{
		"missing file":   {"test", "s", filepath.Join(t.TempDir(), "none.json")},
		"not an object":  {"test", "s", testFile(t, `[1]`)},
		"no workflow id": {"test", "s", testFile(t, `{"input":{}}`)},
		"server error":   {"test", "s", testFile(t, `{"workflowId":"deploy"}`)},
	} {
		if _, err := testutil.Run(t, WorkflowCmd(c), args...); err == nil {
			t.Errorf("%s: expected an error", name)
		}
	}
}
