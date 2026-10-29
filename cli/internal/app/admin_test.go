// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package app

import (
	"net/http"
	"net/http/httptest"
	"os"
	"testing"
)

func TestMain_supervisorsAndWorkers(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_, _ = w.Write([]byte(`{"data":[]}`))
	}))
	defer srv.Close()
	old := os.Args
	defer func() { os.Args = old }()
	for _, name := range []string{"supervisors", "workers"} {
		os.Args = []string{"yukta-cli", "--url", srv.URL, name}
		if code := Main(); code != 0 {
			t.Errorf("%s: exit %d", name, code)
		}
	}
}
