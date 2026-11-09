// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package client

import (
	"context"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// lastCall answers every request with a fixed body and remembers the last one.
type lastCall struct {
	srv    *httptest.Server
	method string
	uri    string
	body   string
	answer string
	status int
}

func newLastCall(t *testing.T, answer string) (*lastCall, *Client) {
	t.Helper()
	r := &lastCall{answer: answer, status: http.StatusOK}
	r.srv = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		raw, _ := io.ReadAll(req.Body)
		r.method, r.uri, r.body = req.Method, req.URL.RequestURI(), string(raw)
		w.WriteHeader(r.status)
		_, _ = w.Write([]byte(r.answer))
	}))
	t.Cleanup(r.srv.Close)
	return r, NewClient(r.srv.URL)
}

func TestSupervisorCallsUseTheirPathsAndMethods(t *testing.T) {
	r, c := newLastCall(t, `{"data":{}}`)
	tests := []struct {
		want string
		call func() (any, error)
	}{
		{"GET /api/supervisor-engines/rules/presets", func() (any, error) { return c.GetSupervisorPresets("rules") }},
		{"GET /api/supervisors/p/credential", func() (any, error) { return c.GetAgentCredential("p") }},
		{"GET /api/supervisors/p/versions", func() (any, error) { return c.GetSupervisorVersions("p") }},
		{"POST /api/supervisors/p/rollback?version=2", func() (any, error) { return c.RollbackSupervisor("p", 2) }},
		{"GET /api/supervisors/p/engine/rules", func() (any, error) { return c.GetRules("p") }},
		{"PUT /api/supervisors/p/engine/rules", func() (any, error) { return c.SaveRules("p", []byte(`{"rules":[]}`)) }},
		{"POST /api/supervisors/p/engine/rules/replay", func() (any, error) { return c.ReplayRules("p", "e", nil) }},
		{"POST /api/supervisors/p/credential", func() (any, error) { return c.IssueAgentCredential("p", 0) }},
		{"POST /api/supervisors/p/credential?ttlHours=24", func() (any, error) { return c.IssueAgentCredential("p", 24) }},
		{"DELETE /api/supervisors/p/credential", func() (any, error) { return c.RevokeAgentCredential("p") }},
		{"GET /api/supervision", func() (any, error) { return c.GetSupervision("", "", "", "") }},
		{"GET /api/supervision?execution=e&node=n&session=s&workflow=w", func() (any, error) { return c.GetSupervision("s", "w", "e", "n") }},
		{"GET /api/supervision/ownership?session=s&workflow=w", func() (any, error) { return c.GetOwnership("s", "w") }},
		{"GET /api/supervisor-contract", c.GetSupervisorContract},
		{"GET /api/supervisors/decisions", func() (any, error) { return c.GetSupervisorDecisions("", 0) }},
		{"GET /api/supervisors/decisions?limit=5&supervisor=sup", func() (any, error) { return c.GetSupervisorDecisions("sup", 5) }},
		{"PUT /api/supervisors/p", func() (any, error) { return c.SaveSupervisor([]byte(`{"id":"p"}`)) }},
		{"POST /api/supervisors/validate", func() (any, error) { return c.ValidateSupervisor([]byte(`{}`)) }},
		{"DELETE /api/supervisors/p", func() (any, error) { return c.DeleteSupervisor("p") }},
		{"PUT /api/supervisors/p/assignments", func() (any, error) { return c.SetAssignmentsBody("p", []byte(`[]`), false) }},
		{"PUT /api/supervisors/p/assignments?unlock=true", func() (any, error) { return c.SetAssignmentsBody("p", []byte(`[]`), true) }},
		{"PUT /api/supervisors/p/assignments", func() (any, error) {
			return c.SetAssignments("p", []Assignment{{Target: "server", Enabled: true}}, false)
		}},
	}
	for _, tt := range tests {
		if _, err := tt.call(); err != nil || r.method+" "+r.uri != tt.want {
			t.Errorf("want %q, got %q (%v)", tt.want, r.method+" "+r.uri, err)
		}
	}
}

func TestSupervisorCallsRefuseWhatCannotBeSent(t *testing.T) {
	_, c := newLastCall(t, `{"data":{}}`)
	if _, err := c.RollbackSupervisor("p", 0); err == nil {
		t.Error("a version below 1 must be refused")
	}
	if _, err := c.ReplaySupervisor("p", "", ""); err == nil {
		t.Error("a replay needs an execution")
	}
	if _, err := c.ReplaySupervisor("p", "e", "not json"); err == nil {
		t.Error("settings that are not JSON must be refused")
	}
	if _, err := c.ReplayRules("p", "", nil); err == nil {
		t.Error("a rules replay needs an execution")
	}
	if _, err := c.ReplayRules("p", "e", []byte("not json")); err == nil {
		t.Error("draft rules that are not JSON must be refused")
	}
	for _, body := range []string{"not json", `{}`, `{"id":""}`} {
		if _, err := c.SaveSupervisor([]byte(body)); err == nil {
			t.Errorf("a supervisor file %q needs an id", body)
		}
	}
	if err := c.AgentSend("/api/supervisors", []byte(`{}`)); err == nil {
		t.Error("only paths of the agent API may be posted to")
	}
}

func TestReplayCarriesTheExecutionAndTheDraftSettings(t *testing.T) {
	r, c := newLastCall(t, `{"data":[]}`)
	if _, err := c.ReplaySupervisor("p", "e1", ""); err != nil {
		t.Fatal(err)
	}
	if r.method != "POST" || r.uri != "/api/supervisors/p/replay" || !strings.Contains(r.body, `"executionId":"e1"`) || strings.Contains(r.body, "settings") {
		t.Errorf("unexpected replay: %s %s %s", r.method, r.uri, r.body)
	}
	if _, err := c.ReplayRules("p", "e1", []byte(`{"rules":[1]}`)); err != nil || !strings.Contains(r.body, `"settings":{"rules":[1]}`) {
		t.Errorf("the draft rules must travel as settings: %s (%v)", r.body, err)
	}
	if _, err := c.ReplaySupervisor("p", "e1", `{"rules":[]}`); err != nil || !strings.Contains(r.body, `"settings":{"rules":[]}`) {
		t.Errorf("the draft settings must travel: %s (%v)", r.body, err)
	}
}

const ownedByHandAndByDefinition = `{"data":[
  {"scope":"workflow:s/w","enabled":true,"source":"MANUAL","locked":false},
  {"scope":"run:s/x","enabled":false,"source":"MANUAL","locked":true},
  {"scope":"session:s","enabled":true,"source":"DEFINITION","locked":false}]}`

func TestAssignmentsAreChangedOnlyForTheOnesMadeByHand(t *testing.T) {
	r, c := newLastCall(t, ownedByHandAndByDefinition)

	// assigning a target it owns already replaces it; the definition's one is never sent back
	r.status = http.StatusOK
	if _, err := c.Assign("p", "workflow:s/w", false, true); err != nil {
		t.Fatal(err)
	}
	if r.method != "PUT" || strings.Contains(r.body, "session:s") || !strings.Contains(r.body, `"target":"workflow:s/w","enabled":false,"locked":true`) {
		t.Errorf("unexpected assign: %s %s %s", r.method, r.uri, r.body)
	}
	if strings.Count(r.body, "workflow:s/w") != 1 || !strings.Contains(r.body, "run:s/x") {
		t.Errorf("the other assignment must stay and the target appear once: %s", r.body)
	}

	if _, err := c.Unassign("p", "run:s/x", true); err != nil {
		t.Fatal(err)
	}
	if r.uri != "/api/supervisors/p/assignments?unlock=true" || strings.Contains(r.body, "run:s/x") {
		t.Errorf("unexpected unassign: %s %s", r.uri, r.body)
	}
	if _, err := c.Unassign("p", "server", false); err == nil || !strings.Contains(err.Error(), "does not own") {
		t.Errorf("a target it does not own by hand must be named: %v", err)
	}

	if _, err := c.CheckAssign("p", []string{"server"}); err != nil {
		t.Fatal(err)
	}
	if r.method != "POST" || r.uri != "/api/supervisors/p/assignments/check" || !strings.Contains(r.body, `"target":"server","enabled":true`) {
		t.Errorf("unexpected check: %s %s %s", r.method, r.uri, r.body)
	}
}

func TestParentsAreSetAndTheDelegationModesAreKeptOrChanged(t *testing.T) {
	r, c := newLastCall(t, `{"data":{"id":"p","type":"rules","description":"d","dryRun":false,"settings":{"a":1},"delegation":{"INPUT":{"mode":"ALL"}}}}`)

	if _, err := c.SetParents("p", []string{"boss"}, map[string]string{"approval": "any"}); err != nil {
		t.Fatal(err)
	}
	if r.method != "PUT" || r.uri != "/api/supervisors/p" {
		t.Errorf("unexpected call: %s %s", r.method, r.uri)
	}
	for _, want := range []string{`"parents":["boss"]`, `"APPROVAL":{"mode":"ANY"}`, `"INPUT":{"mode":"ALL"}`, `"type":"rules"`} {
		if !strings.Contains(r.body, want) {
			t.Errorf("%s missing from %s", want, r.body)
		}
	}
}

func TestAnErrorAnswerFailsTheCallAndARefusalIsRecognised(t *testing.T) {
	r, c := newLastCall(t, `{"errors":[{"field":"code","message":"CONTROLLED_BY_SUPERVISOR"}]}`)
	r.status = http.StatusConflict
	_, err := c.GetAgentCredential("p")
	if err == nil || !IsRefusal(err) {
		t.Errorf("a 409 CONTROLLED_BY_SUPERVISOR must be a refusal: %v", err)
	}

	r.answer = `{"errors":[{"field":"code","message":"CONTROL_REJECTED"}]}`
	r.status = http.StatusForbidden
	if _, err = c.GetAgentCredential("p"); !IsRefusal(err) {
		t.Errorf("a 403 CONTROL_REJECTED must be a refusal: %v", err)
	}

	r.answer = `{"errors":[{"field":"name","message":"taken"}]}`
	r.status = http.StatusConflict
	if _, err = c.GetAgentCredential("p"); IsRefusal(err) {
		t.Error("another conflict is not a refusal")
	}
	r.answer = `not json`
	if _, err = c.GetAgentCredential("p"); IsRefusal(err) {
		t.Error("an answer that is not JSON is not a refusal")
	}
	if IsRefusal(errors.New("plain")) || IsRefusal(nil) {
		t.Error("an error that did not come from the server is not a refusal")
	}
}

func TestTheAgentStreamPassesEachSignalWithItsCursor(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		if req.URL.RequestURI() != "/api/agent/v1/events?after=-1" {
			http.Error(w, "unexpected "+req.URL.RequestURI(), http.StatusBadRequest)
			return
		}
		w.Header().Set("Content-Type", "text/event-stream")
		_, _ = w.Write([]byte(": keep-alive\n\nid: 7\ndata: {\"cursor\":7}\n\nevent: ping\n\nid: 8\ndata: {\"cursor\":8}\n\n"))
	}))
	defer srv.Close()
	c := NewClient(srv.URL)

	var seen []string
	err := c.AgentStream(context.Background(), -1, func(data, cursor string) error {
		seen = append(seen, cursor+"="+data)
		return nil
	})
	if err != nil || len(seen) != 2 || seen[0] != `7={"cursor":7}` || seen[1] != `8={"cursor":8}` {
		t.Errorf("unexpected signals %v (%v)", seen, err)
	}

	stop := errors.New("enough")
	if err := c.AgentStream(context.Background(), -1, func(string, string) error { return stop }); !errors.Is(err, stop) {
		t.Errorf("an error of the callback must end the stream: %v", err)
	}
	if err := NewClient("http://127.0.0.1:1").AgentStream(context.Background(), 0, func(string, string) error { return nil }); err == nil {
		t.Error("a server that is down must fail the stream")
	}
}

func TestAnAgentDirectiveIsPostedToTheAgentApi(t *testing.T) {
	r, c := newLastCall(t, `{"data":{}}`)
	if err := c.AgentSend("/api/agent/v1/directives", []byte(`{"key":"k"}`)); err != nil {
		t.Fatal(err)
	}
	if r.method != "POST" || r.uri != "/api/agent/v1/directives" || r.body != `{"key":"k"}` {
		t.Errorf("unexpected post: %s %s %s", r.method, r.uri, r.body)
	}
}
