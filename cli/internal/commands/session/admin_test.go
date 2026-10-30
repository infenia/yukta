// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package session

import (
	"net/http"
	"net/http/httptest"
	"testing"

	"com.infenia.yukta/go-cli/internal/client"
	"com.infenia.yukta/go-cli/internal/testutil"
)

func TestSessionConfigCommand(t *testing.T) {
	var got string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		got = r.URL.Path
		_, _ = w.Write([]byte(`{"data":{}}`))
	}))
	defer srv.Close()
	if _, err := testutil.Run(t, SessionCmd(client.NewClient(srv.URL)), "config", "s1"); err != nil || got != "/api/sessions/s1/config" {
		t.Errorf("got %q, %v", got, err)
	}
}
