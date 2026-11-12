// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package supervisors

import (
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
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
		{[]string{"list"}, "/api/supervisors"},
		{[]string{"show", "s1"}, "/api/supervisors/s1"},
		{[]string{"presets", "rules"}, "/api/supervisor-engines/rules/presets"},
		{[]string{"versions", "s1"}, "/api/supervisors/s1/versions"},
		{[]string{"rollback", "s1", "--version", "2"}, "/api/supervisors/s1/rollback"},
		{[]string{"replay", "s1", "--execution", "e1", "--settings", "{}"}, "/api/supervisors/s1/replay"},
		{[]string{"credential", "create", "s1", "--ttl-hours", "3"}, "/api/supervisors/s1/credential"},
		{[]string{"credential", "revoke", "s1"}, "/api/supervisors/s1/credential"},
		{[]string{"engines"}, "/api/supervisor-engines"},
		{[]string{"engines", "http"}, "/api/supervisor-engines/http"},
		{[]string{"assignments", "s1"}, "/api/supervisors/s1/assignments"},
		{[]string{"credential", "show", "s1"}, "/api/supervisors/s1/credential"},
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

func TestSummaryReadsTheSupervisionSummary(t *testing.T) {
	var got string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		got = r.URL.RequestURI()
		_, _ = w.Write([]byte(`{"data":{"unowned":1,"unownedWorkflows":[]}}`))
	}))
	defer srv.Close()
	cmd := SummaryCmd(client.NewClient(srv.URL))
	cmd.SetArgs(nil)
	if err := cmd.Execute(); err != nil || got != "/api/supervision/summary" {
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

// assignmentServer answers like the server: s1 owns server by hand and a definition owns a session.
func assignmentServer(t *testing.T, calls *[]string) *httptest.Server {
	t.Helper()
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		*calls = append(*calls, r.Method+" "+r.URL.RequestURI()+" "+string(body))
		switch {
		case r.Method == "GET" && r.URL.Path == "/api/supervisors/s1/assignments":
			_, _ = w.Write([]byte(`{"data":[{"scope":"server","enabled":true,"source":"MANUAL","locked":true},` +
				`{"scope":"session:a","enabled":true,"source":"DEFINITION"}]}`))
		case r.Method == "GET" && r.URL.Path == "/api/supervisors/s1":
			_, _ = w.Write([]byte(`{"data":{"type":"rules","settings":{},"dryRun":false,` +
				`"delegation":{"INPUT":{"mode":"ALL"}}}}`))
		default:
			_, _ = w.Write([]byte(`{"data":[]}`))
		}
	}))
}

func TestAssignmentAndParentCommands(t *testing.T) {
	var calls []string
	srv := assignmentServer(t, &calls)
	defer srv.Close()
	file := filepath.Join(t.TempDir(), "a.json")
	if err := os.WriteFile(file, []byte(`[{"target":"run:s/w"}]`), 0o600); err != nil {
		t.Fatal(err)
	}

	tests := []struct {
		args []string
		want string
	}{
		{[]string{"assign", "s1", "run:s/w", "--locked"},
			`PUT /api/supervisors/s1/assignments [{"target":"server","enabled":true,"locked":true},{"target":"run:s/w","enabled":true,"locked":true}]`},
		{[]string{"assign", "s1", "server", "--disabled"},
			`PUT /api/supervisors/s1/assignments [{"target":"server","enabled":false,"locked":false}]`},
		{[]string{"unassign", "s1", "server", "--unlock"}, `PUT /api/supervisors/s1/assignments?unlock=true []`},
		{[]string{"check-assign", "s1", "run:s/w", "workflow:s/x"},
			`POST /api/supervisors/s1/assignments/check [{"target":"server","enabled":true,"locked":true},` +
				`{"target":"run:s/w","enabled":true,"locked":false},{"target":"workflow:s/x","enabled":true,"locked":false}]`},
		{[]string{"set-assignments", "s1", "-f", file}, `PUT /api/supervisors/s1/assignments [{"target":"run:s/w"}]`},
		{[]string{"parents", "set", "s1", "--parent", "lead", "--mode", "approval=any"},
			`PUT /api/supervisors/s1 {"delegation":{"APPROVAL":{"mode":"ANY"},"INPUT":{"mode":"ALL"}},` +
				`"description":null,"dryRun":false,"id":"s1","parents":["lead"],"settings":{},"type":"rules"}`},
	}
	for _, tt := range tests {
		calls = nil
		cmd := SupervisorsCmd(client.NewClient(srv.URL))
		cmd.SetArgs(tt.args)
		if err := cmd.Execute(); err != nil || calls[len(calls)-1] != tt.want {
			t.Errorf("%v:\nwant %s\ngot  %v (%v)", tt.args, tt.want, calls, err)
		}
	}
}

func TestAssignmentCommandsRefuseBadInput(t *testing.T) {
	var calls []string
	srv := assignmentServer(t, &calls)
	defer srv.Close()
	for _, args := range [][]string{
		{"unassign", "s1", "session:a"},
		{"set-assignments", "s1", "-f", filepath.Join(t.TempDir(), "missing.json")},
		{"parents", "set", "s1", "--mode", "approval"},
		{"check-assign", "s1"},
	} {
		cmd := SupervisorsCmd(client.NewClient(srv.URL))
		cmd.SetArgs(args)
		cmd.SilenceUsage, cmd.SilenceErrors = true, true
		if err := cmd.Execute(); err == nil {
			t.Errorf("%v must fail", args)
		}
	}
}

func TestRulesCommands(t *testing.T) {
	var path, method, body string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		raw, _ := io.ReadAll(r.Body)
		path, method, body = r.URL.Path, r.Method, string(raw)
		_, _ = w.Write([]byte(`{"data":{}}`))
	}))
	defer srv.Close()
	file := t.TempDir() + "/rules.json"
	if err := os.WriteFile(file, []byte(`{"rules":["a"]}`), 0o600); err != nil {
		t.Fatal(err)
	}
	for _, tt := range []struct {
		args                     []string
		wantMethod, wantPath, in string
	}{
		{[]string{"rules", "get", "s1"}, "GET", "/api/supervisors/s1/engine/rules", ""},
		{[]string{"rules", "set", "s1", "-f", file}, "PUT", "/api/supervisors/s1/engine/rules", `"rules":["a"]`},
		{[]string{"rules", "replay", "s1", "--execution", "e1"}, "POST", "/api/supervisors/s1/engine/rules/replay", `"executionId":"e1"`},
		{[]string{"rules", "replay", "s1", "--execution", "e1", "-f", file}, "POST", "/api/supervisors/s1/engine/rules/replay", `"settings":{"rules":["a"]}`},
	} {
		cmd := SupervisorsCmd(client.NewClient(srv.URL))
		cmd.SetArgs(tt.args)
		if err := cmd.Execute(); err != nil || method != tt.wantMethod || path != tt.wantPath || !strings.Contains(body, tt.in) {
			t.Errorf("%v: got %s %s %s (%v)", tt.args, method, path, body, err)
		}
	}
	for _, args := range [][]string{
		{"rules", "set", "s1", "-f", "/no/such/file"},
		{"rules", "replay", "s1", "--execution", "e1", "-f", "/no/such/file"},
		{"rules", "replay", "s1"},
	} {
		cmd := SupervisorsCmd(client.NewClient(srv.URL))
		cmd.SetArgs(args)
		cmd.SilenceUsage, cmd.SilenceErrors = true, true
		if err := cmd.Execute(); err == nil {
			t.Errorf("%v: want an error", args)
		}
	}
}
