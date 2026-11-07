// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package supervisors

import (
	"net/http"
	"net/http/httptest"
	"testing"

	"com.infenia.yukta/go-cli/internal/client"
)

func TestSupervisorsCommands(t *testing.T) {
	var got string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		got = r.URL.Path
		_, _ = w.Write([]byte(`{"data":[]}`))
	}))
	defer srv.Close()

	tests := []struct {
		args []string
		want string
	}{
		{nil, "/api/supervisors/status"},
		{[]string{"types"}, "/api/supervisor-types"},
		{[]string{"types", "composite"}, "/api/supervisor-types/composite"},
		{[]string{"list"}, "/api/supervisors"},
		{[]string{"show", "s1"}, "/api/supervisors/s1"},
	}
	for _, tt := range tests {
		cmd := SupervisorsCmd(client.NewClient(srv.URL))
		cmd.SetArgs(tt.args)
		if err := cmd.Execute(); err != nil || got != tt.want {
			t.Errorf("%v: want %q, got %q (%v)", tt.args, tt.want, got, err)
		}
	}
}

func TestShowNeedsAnId(t *testing.T) {
	cmd := SupervisorsCmd(client.NewClient("http://127.0.0.1:1"))
	cmd.SetArgs([]string{"show"})
	cmd.SilenceUsage, cmd.SilenceErrors = true, true
	if err := cmd.Execute(); err == nil {
		t.Error("show without an id must fail")
	}
}
