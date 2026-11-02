// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package client

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// inputServer answers every call with status and body, and records the last request.
type inputServer struct {
	method, path, query, body string
}

func (s *inputServer) start(t *testing.T, status int, body string) *Client {
	t.Helper()
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		s.method, s.path, s.query = r.Method, r.URL.EscapedPath(), r.URL.RawQuery
		b, _ := io.ReadAll(r.Body)
		s.body = string(b)
		w.WriteHeader(status)
		_, _ = w.Write([]byte(body))
	}))
	t.Cleanup(server.Close)
	return NewClient(server.URL)
}

const oneInput = `{"data":{"id":"r1","sessionId":"s","executionId":"e","nodeId":"n","state":"ANSWERED",` +
	`"version":1,"kind":"CHOICE","prompt":"Which?","options":[{"id":"ok","label":"Go"}],` +
	`"answer":{"decision":"ok","responderType":"HUMAN","responderId":"key:ann","answeredAt":"t"}}}`

func TestInputCalls_emptyIDs(t *testing.T) {
	c := NewClient("http://localhost:9858")
	if _, err := c.ListInputs("", ""); err == nil || !strings.Contains(err.Error(), "sessionID") {
		t.Errorf("ListInputs: expected sessionID error, got %v", err)
	}
	if _, err := c.GetInput("", "r"); err == nil || !strings.Contains(err.Error(), "executionID") {
		t.Errorf("GetInput: expected executionID error, got %v", err)
	}
	if _, err := c.GetInput("e", ""); err == nil || !strings.Contains(err.Error(), "requestID") {
		t.Errorf("GetInput: expected requestID error, got %v", err)
	}
	if _, err := c.AnswerInput("", "r", AnswerInputRequest{Decision: "ok"}); err == nil {
		t.Error("AnswerInput: expected an error")
	}
	if _, err := c.AnswerInput("e", "r", AnswerInputRequest{}); err == nil || !strings.Contains(err.Error(), "decision") {
		t.Errorf("AnswerInput: expected decision error, got %v", err)
	}
	bad := AnswerInputRequest{Decision: "ok", Data: json.RawMessage(`{nope`)}
	if _, err := c.AnswerInput("e", "r", bad); err == nil || !strings.Contains(err.Error(), "valid JSON") {
		t.Errorf("AnswerInput: expected data error, got %v", err)
	}
	if _, err := c.EscalateInput("e", "", "why", ""); err == nil {
		t.Error("EscalateInput: expected an error")
	}
	if _, err := c.EscalateInput("e", "r", "", ""); err == nil || !strings.Contains(err.Error(), "reason") {
		t.Errorf("EscalateInput: expected reason error, got %v", err)
	}
}

func TestListInputs_pathStatesAndPages(t *testing.T) {
	var queries []string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.EscapedPath() != "/api/sessions/a%2Fb/inputs" {
			t.Errorf("path %q", r.URL.EscapedPath())
		}
		queries = append(queries, r.URL.RawQuery)
		if r.URL.Query().Get("cursor") == "" {
			w.Header().Set("X-Next-Cursor", "r2")
			_, _ = w.Write([]byte(`{"data":[{"id":"r1"},{"id":"r2"}]}`))
			return
		}
		_, _ = w.Write([]byte(`{"data":[{"id":"r3"}]}`))
	}))
	defer server.Close()

	inputs, err := NewClient(server.URL).ListInputs("a/b", "OPEN,ANSWERED")
	if err != nil {
		t.Fatal(err)
	}
	if len(inputs) != 3 || inputs[2].ID != "r3" {
		t.Errorf("inputs %+v", inputs)
	}
	if len(queries) != 2 || queries[0] != "state=OPEN%2CANSWERED" || queries[1] != "state=OPEN%2CANSWERED&cursor=r2" {
		t.Errorf("queries %q", queries)
	}
}

func TestListInputs_withoutStatesSendsNoQuery(t *testing.T) {
	s := &inputServer{}
	c := s.start(t, 200, `{"data":[]}`)
	if _, err := c.ListInputs("s1", ""); err != nil {
		t.Fatal(err)
	}
	if s.path != "/api/sessions/s1/inputs" || s.query != "" {
		t.Errorf("path %q query %q", s.path, s.query)
	}
}

func TestListAllInputs_readsEverySession(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/sessions":
			_, _ = w.Write([]byte(`{"data":["s1","s2"]}`))
		case "/api/sessions/s1/inputs":
			_, _ = w.Write([]byte(`{"data":[{"id":"r1","sessionId":"s1"}]}`))
		default:
			_, _ = w.Write([]byte(`{"data":[{"id":"r2","sessionId":"s2"}]}`))
		}
	}))
	defer server.Close()

	inputs, err := NewClient(server.URL).ListAllInputs("")
	if err != nil {
		t.Fatal(err)
	}
	if len(inputs) != 2 || inputs[0].ID != "r1" || inputs[1].SessionID != "s2" {
		t.Errorf("inputs %+v", inputs)
	}
}

func TestListAllInputs_failures(t *testing.T) {
	s := &inputServer{}
	if _, err := s.start(t, 403, `{"message":"no"}`).ListAllInputs(""); err == nil || !strings.Contains(err.Error(), "--session") {
		t.Errorf("expected a sessions error, got %v", err)
	}

	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/api/sessions" {
			_, _ = w.Write([]byte(`{"data":["s1"]}`))
			return
		}
		http.Error(w, `{"message":"no"}`, http.StatusForbidden)
	}))
	defer server.Close()
	if _, err := NewClient(server.URL).ListAllInputs(""); err == nil || !strings.Contains(err.Error(), "session s1") {
		t.Errorf("expected a session error, got %v", err)
	}
}

func TestGetInput_readsTheRequestAndItsAnswer(t *testing.T) {
	s := &inputServer{}
	in, err := s.start(t, 200, oneInput).GetInput("e/1", "r1")
	if err != nil {
		t.Fatal(err)
	}
	if s.method != "GET" || s.path != "/api/executions/e%2F1/inputs/r1" {
		t.Errorf("%s %s", s.method, s.path)
	}
	if in.Options[0].Label != "Go" || in.Answer == nil || in.Answer.ResponderID != "key:ann" || in.Version != 1 {
		t.Errorf("input %+v", in)
	}
}

func TestAnswerInput_sendsTheBody(t *testing.T) {
	s := &inputServer{}
	version := int64(0)
	answer := AnswerInputRequest{
		Decision: "ok", Text: "fine", Data: json.RawMessage(`{"n":1}`), Rationale: "why", ExpectedVersion: &version,
	}
	if _, err := s.start(t, 200, oneInput).AnswerInput("e", "r1", answer); err != nil {
		t.Fatal(err)
	}
	want := `{"decision":"ok","text":"fine","data":{"n":1},"rationale":"why","expectedVersion":0}`
	if s.method != "POST" || s.path != "/api/executions/e/inputs/r1/answer" || s.body != want {
		t.Errorf("%s %s %s", s.method, s.path, s.body)
	}

	if _, err := s.start(t, 200, oneInput).AnswerInput("e", "r1", AnswerInputRequest{Decision: "ok"}); err != nil {
		t.Fatal(err)
	}
	if s.body != `{"decision":"ok"}` {
		t.Errorf("body %s", s.body)
	}
}

func TestEscalateInput_sendsTheBody(t *testing.T) {
	s := &inputServer{}
	if _, err := s.start(t, 200, oneInput).EscalateInput("e", "r1", "not mine", "ask ops"); err != nil {
		t.Fatal(err)
	}
	if s.path != "/api/executions/e/inputs/r1/escalate" || s.body != `{"reason":"not mine","advice":"ask ops"}` {
		t.Errorf("%s %s", s.path, s.body)
	}
}

func TestInputCalls_serverAndBodyErrors(t *testing.T) {
	s := &inputServer{}
	_, err := s.start(t, 409, `{"message":"Input request r1 is already answered: ok by HUMAN:key:ann"}`).
		AnswerInput("e", "r1", AnswerInputRequest{Decision: "ok"})
	if status, ok := APIStatus(err); !ok || status != 409 || !strings.Contains(err.Error(), "already answered") {
		t.Errorf("expected the 409, got %v", err)
	}
	if _, err := s.start(t, 200, `not json`).GetInput("e", "r1"); err == nil || !strings.Contains(err.Error(), "unmarshal") {
		t.Errorf("expected an unmarshal error, got %v", err)
	}
	remote := NewClient("http://example.com")
	remote.Token = "secret"
	if _, err := remote.GetInput("e", "r1"); err == nil || !strings.Contains(err.Error(), "plain http") {
		t.Errorf("expected the token transport error, got %v", err)
	}
}

func TestSeparator(t *testing.T) {
	if separator("/api/x") != "?" || separator("/api/x?a=b") != "&" {
		t.Error("separator")
	}
}
