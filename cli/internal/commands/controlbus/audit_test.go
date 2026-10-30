// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package controlbus

import (
	"net/http"
	"net/http/httptest"
	"testing"

	"com.infenia.yukta/go-cli/internal/client"
	"com.infenia.yukta/go-cli/internal/testutil"
)

func TestAuditCommand(t *testing.T) {
	var got string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		got = r.URL.RequestURI()
		_, _ = w.Write([]byte(`{"data":[]}`))
	}))
	defer srv.Close()
	if _, err := testutil.Run(t, ControlBusCmd(client.NewClient(srv.URL)), "audit", "--limit", "3", "--execution", "e1"); err != nil {
		t.Fatal(err)
	}
	if want := "/api/control/audit?executionId=e1&limit=3"; got != want {
		t.Errorf("got %q, want %q", got, want)
	}
}
