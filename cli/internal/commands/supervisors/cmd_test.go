// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package supervisors

import (
	"net/http"
	"net/http/httptest"
	"os"
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
		{[]string{"presets", "rule-supervisor"}, "/api/supervisor-types/rule-supervisor/presets"},
		{[]string{"versions", "s1"}, "/api/supervisors/s1/versions"},
		{[]string{"rollback", "s1", "--version", "2"}, "/api/supervisors/s1/rollback"},
		{[]string{"replay", "s1", "--execution", "e1", "--settings", "{}"}, "/api/supervisors/s1/replay"},
		{[]string{"credential", "create", "s1", "--ttl-hours", "3"}, "/api/supervisors/s1/credential"},
		{[]string{"credential", "revoke", "s1"}, "/api/supervisors/s1/credential"},
		{[]string{"engines"}, "/api/supervisor-types"},
		{[]string{"engines", "http"}, "/api/supervisor-types/http"},
		{[]string{"decisions", "--supervisor", "s1", "--limit", "5"}, "/api/supervisors/decisions"},
		{[]string{"delete", "s1"}, "/api/supervisors/s1"},
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

func TestSupervisorManagementRefusesBadInput(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_, _ = w.Write([]byte(`{"data":{}}`))
	}))
	defer srv.Close()
	for _, args := range [][]string{
		{"rollback", "s1", "--version", "0"},
		{"replay", "s1", "--execution", "e1", "--settings", "not json"},
		{"rollback", "s1"},
		{"replay", "s1"},
	} {
		cmd := SupervisorsCmd(client.NewClient(srv.URL))
		cmd.SetArgs(args)
		cmd.SilenceUsage = true
		cmd.SilenceErrors = true
		if err := cmd.Execute(); err == nil {
			t.Errorf("%v: want an error", args)
		}
	}
	if _, err := client.NewClient(srv.URL).ReplaySupervisor("s1", "", ""); err == nil {
		t.Error("an empty execution should be refused")
	}
}

func TestSaveAndValidateSendTheFile(t *testing.T) {
	var path, method string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		path, method = r.URL.Path, r.Method
		_, _ = w.Write([]byte(`{"data":{}}`))
	}))
	defer srv.Close()
	file := t.TempDir() + "/s.json"
	if err := os.WriteFile(file, []byte(`{"id":"s9","type":"http"}`), 0o600); err != nil {
		t.Fatal(err)
	}
	for _, tt := range []struct{ name, wantPath, wantMethod string }{
		{"save", "/api/supervisors/s9", "PUT"},
		{"validate", "/api/supervisors/validate", "POST"},
	} {
		cmd := SupervisorsCmd(client.NewClient(srv.URL))
		cmd.SetArgs([]string{tt.name, "-f", file})
		if err := cmd.Execute(); err != nil || path != tt.wantPath || method != tt.wantMethod {
			t.Errorf("%s: got %s %s (%v)", tt.name, method, path, err)
		}
	}
	for _, body := range []string{`not json`, `{"type":"http"}`} {
		bad := t.TempDir() + "/b.json"
		_ = os.WriteFile(bad, []byte(body), 0o600)
		cmd := SupervisorsCmd(client.NewClient(srv.URL))
		cmd.SetArgs([]string{"save", "-f", bad})
		cmd.SilenceUsage, cmd.SilenceErrors = true, true
		if err := cmd.Execute(); err == nil {
			t.Errorf("%q should be refused", body)
		}
	}
	cmd := SupervisorsCmd(client.NewClient(srv.URL))
	cmd.SetArgs([]string{"save", "-f", "/no/such/file"})
	cmd.SilenceUsage, cmd.SilenceErrors = true, true
	if err := cmd.Execute(); err == nil {
		t.Error("a missing file should be refused")
	}
}

func TestSupervisionAndContractRead(t *testing.T) {
	var got string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		got = r.URL.RequestURI()
		_, _ = w.Write([]byte(`{"data":{}}`))
	}))
	defer srv.Close()
	cmd := SupervisionCmd(client.NewClient(srv.URL))
	cmd.SetArgs([]string{"--session", "s", "--workflow", "w", "--node", "n"})
	if err := cmd.Execute(); err != nil || got != "/api/supervision?node=n&session=s&workflow=w" {
		t.Errorf("got %q (%v)", got, err)
	}
	cmd = SupervisionCmd(client.NewClient(srv.URL))
	cmd.SetArgs(nil)
	if err := cmd.Execute(); err != nil || got != "/api/supervision" {
		t.Errorf("got %q (%v)", got, err)
	}
	cmd = ContractCmd(client.NewClient(srv.URL))
	if err := cmd.Execute(); err != nil || got != "/api/supervisor-contract" {
		t.Errorf("got %q (%v)", got, err)
	}
}

func TestOwnershipNeedsASessionAndAWorkflow(t *testing.T) {
	var got string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		got = r.URL.RequestURI()
		_, _ = w.Write([]byte(`{"data":{}}`))
	}))
	defer srv.Close()
	cmd := OwnershipCmd(client.NewClient(srv.URL))
	cmd.SetArgs([]string{"--session", "s", "--workflow", "w"})
	if err := cmd.Execute(); err != nil || got != "/api/supervision/ownership?session=s&workflow=w" {
		t.Errorf("got %q (%v)", got, err)
	}
	cmd = OwnershipCmd(client.NewClient(srv.URL))
	cmd.SetArgs([]string{"--session", "s"})
	cmd.SilenceUsage, cmd.SilenceErrors = true, true
	if err := cmd.Execute(); err == nil {
		t.Error("a workflow is required")
	}
}
