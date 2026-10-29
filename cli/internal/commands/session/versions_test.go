// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package session

import (
	"io"
	"net/http"
	"net/http/httptest"
	"testing"

	"com.infenia.yukta/go-cli/internal/client"
	"com.infenia.yukta/go-cli/internal/testutil"
)

func TestVersionApprovalAndValidateCommands(t *testing.T) {
	var got string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		got = r.Method + " " + r.URL.RequestURI() + " " + string(body)
		_, _ = w.Write([]byte(`{"data":{"ok":true}}`))
	}))
	defer srv.Close()

	tests := []struct {
		args []string
		want string
	}{
		{[]string{"approvals", "s1"}, "GET /api/sessions/s1/approvals "},
		{[]string{"validate", `{"id":"w"}`}, `POST /api/sessions/validate {"id":"w"}`},
		{[]string{"workflow", "versions", "s1", "w1"}, "GET /api/sessions/s1/workflows/w1/versions "},
		{[]string{"workflow", "diff", "s1", "w1", "--from", "2"}, "GET /api/sessions/s1/workflows/w1/versions/diff?from=2 "},
		{[]string{"workflow", "diff", "s1", "w1", "--from", "2", "--to", "3"}, "GET /api/sessions/s1/workflows/w1/versions/diff?from=2&to=3 "},
		{[]string{"workflow", "rollback", "s1", "w1", "2"}, "POST /api/sessions/s1/workflows/w1/rollback?version=2 "},
		{[]string{"workflow", "note", "s1", "w1", "2", "--text", "ok"}, "POST /api/sessions/s1/workflows/w1/versions/note?note=ok&version=2 "},
		{[]string{"workflow", "note", "s1", "w1", "2"}, "POST /api/sessions/s1/workflows/w1/versions/note?version=2 "},
	}
	for _, tt := range tests {
		got = ""
		if _, err := testutil.Run(t, SessionCmd(client.NewClient(srv.URL)), tt.args...); err != nil {
			t.Fatalf("%v: %v", tt.args, err)
		}
		if got != tt.want {
			t.Errorf("%v: got %q, want %q", tt.args, got, tt.want)
		}
	}
}

func TestValidateCmd_mistypedPathAndMissingFrom(t *testing.T) {
	c := client.NewClient("http://127.0.0.1:1")
	if _, err := testutil.Run(t, SessionCmd(c), "validate", "no-such-file.json"); err == nil {
		t.Error("expected an error for a mistyped path")
	}
	if _, err := testutil.Run(t, SessionCmd(c), "workflow", "diff", "s", "w"); err == nil {
		t.Error("expected an error without --from")
	}
}
