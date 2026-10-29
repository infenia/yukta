// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package executions

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"com.infenia.yukta/go-cli/internal/client"
	"com.infenia.yukta/go-cli/internal/testutil"
)

func TestRecoverPriorityAndSearchCommands(t *testing.T) {
	var got string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		got = r.Method + " " + r.URL.RequestURI()
		_, _ = w.Write([]byte(`{"data":{}}`))
	}))
	defer srv.Close()
	for args, want := range map[string]string{
		"recover e1":                   "POST /api/executions/e1/recover",
		"priority e1 3":                "POST /api/executions/e1/priority?priority=3",
		"search --session s --limit 4": "GET /api/executions?limit=4&sessionId=s",
	} {
		got = ""
		if _, err := testutil.Run(t, ExecutionsCmd(client.NewClient(srv.URL)), strings.Fields(args)...); err != nil || got != want {
			t.Errorf("%s: got %q, %v", args, got, err)
		}
	}
	if _, err := testutil.Run(t, ExecutionsCmd(client.NewClient(srv.URL)), "priority", "e1", "high"); err == nil {
		t.Error("expected an error for a non-numeric priority")
	}
}
