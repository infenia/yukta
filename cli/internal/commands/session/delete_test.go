// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package session

import (
	"bytes"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"com.infenia.yukta/go-cli/internal/client"
)

func runDelete(t *testing.T, status int, input string, args ...string) (string, string, []string, error) {
	var calls []string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls = append(calls, r.Method+" "+r.URL.RequestURI())
		if status != http.StatusNoContent {
			http.Error(w, `{"message":"2 run(s) are still active: e1, e2"}`, status)
			return
		}
		w.WriteHeader(status)
	}))
	defer server.Close()

	cmd := SessionCmd(client.NewClient(server.URL))
	var out, errOut bytes.Buffer
	cmd.SetOut(&out)
	cmd.SetErr(&errOut)
	cmd.SetIn(strings.NewReader(input))
	cmd.SetArgs(args)
	err := cmd.Execute()
	return out.String(), errOut.String(), calls, err
}

func TestDeleteSessionWithYesDeletesWithoutAsking(t *testing.T) {
	out, errOut, calls, err := runDelete(t, 204, "", "delete", "s1", "--yes")
	if err != nil {
		t.Fatal(err)
	}
	if len(calls) != 1 || calls[0] != "DELETE /api/sessions/s1" {
		t.Errorf("calls = %v", calls)
	}
	if !strings.Contains(out, "Deleted session s1") || errOut != "" {
		t.Errorf("out = %q, err = %q", out, errOut)
	}
}

func TestDeleteSessionAsksFirstAndAnythingButYesStops(t *testing.T) {
	_, errOut, calls, err := runDelete(t, 204, "n\n", "delete", "s1")
	if err == nil || len(calls) != 0 {
		t.Errorf("expected a refusal without a request, err = %v, calls = %v", err, calls)
	}
	if !strings.Contains(errOut, "Delete session s1 with all its workflows? This cannot be undone. [y/N]") {
		t.Errorf("prompt = %q", errOut)
	}
	_, _, calls, err = runDelete(t, 204, "y\n", "delete", "s1")
	if err != nil || len(calls) != 1 {
		t.Errorf("expected a delete after y, err = %v, calls = %v", err, calls)
	}
}

func TestDeleteWorkflowSendsForce(t *testing.T) {
	out, _, calls, err := runDelete(t, 204, "", "workflow", "delete", "s1", "wf 1", "--force", "-y")
	if err != nil {
		t.Fatal(err)
	}
	if len(calls) != 1 || calls[0] != "DELETE /api/sessions/s1/workflows/wf%201?force=true" {
		t.Errorf("calls = %v", calls)
	}
	if !strings.Contains(out, "Deleted workflow wf 1 of session s1") {
		t.Errorf("out = %q", out)
	}
}

func TestDeleteReportsTheServersRefusal(t *testing.T) {
	_, _, _, err := runDelete(t, 409, "", "delete", "s1", "--yes")
	if err == nil || !strings.Contains(err.Error(), "still active") {
		t.Errorf("err = %v", err)
	}
}
