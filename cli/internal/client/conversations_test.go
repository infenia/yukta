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

// conversationServer answers every call with status and body (and the header, when given), and
// records the last request.
type conversationServer struct {
	method, path, query, body string
}

func (s *conversationServer) start(t *testing.T, status int, body string, header ...string) *Client {
	t.Helper()
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		s.method, s.path, s.query = r.Method, r.URL.EscapedPath(), r.URL.RawQuery
		b, _ := io.ReadAll(r.Body)
		s.body = string(b)
		if len(header) == 2 {
			w.Header().Set(header[0], header[1])
		}
		w.WriteHeader(status)
		_, _ = w.Write([]byte(body))
	}))
	t.Cleanup(server.Close)
	c := NewClient(server.URL)
	return c
}

const oneConversation = `{"data":{"id":"c1","executionId":"e1","nodeId":"n","harness":"codex","state":"RUNNING","capabilities":["message","terminal"]}}`

func TestConversationCapabilities(t *testing.T) {
	c := Conversation{Capabilities: []string{"message", "terminal"}}
	if !c.Can("terminal") || c.Can("ask") {
		t.Errorf("Can: %v", c.Capabilities)
	}
}

func TestConversationCallsRefuseAnEmptyID(t *testing.T) {
	c := NewClient("http://localhost:9858")
	calls := map[string]func() error{
		"get":       func() error { _, err := c.GetConversation(""); return err },
		"events":    func() error { _, err := c.ConversationEvents("", 0, 0); return err },
		"send":      func() error { _, err := c.SendConversationMessage("", "x", false); return err },
		"interrupt": func() error { return c.InterruptConversation("") },
		"end":       func() error { return c.EndConversation("", "", "") },
		"input":     func() error { return c.TerminalInput("", []byte("x")) },
		"resize":    func() error { return c.ResizeTerminal("", 80, 24) },
	}
	for name, call := range calls {
		if err := call(); err == nil || !strings.Contains(err.Error(), "conversation id") {
			t.Errorf("%s: %v", name, err)
		}
	}
	if _, err := c.SendConversationMessage("c1", "", false); err == nil || !strings.Contains(err.Error(), "text") {
		t.Errorf("send without text: %v", err)
	}
}

func TestListConversationsSendsTheFilter(t *testing.T) {
	s := &conversationServer{}
	c := s.start(t, 200, `{"data":[{"id":"c1"},{"id":"c2"}]}`)

	got, err := c.ListConversations(ConversationListOptions{SessionID: "s", ExecutionID: "e", State: "RUNNING,IDLE", Limit: 5})

	if err != nil || len(got) != 2 {
		t.Fatalf("got %v, %v", got, err)
	}
	if s.path != "/api/conversations" || s.query != "executionId=e&limit=5&sessionId=s&state=RUNNING%2CIDLE" {
		t.Errorf("request = %s?%s", s.path, s.query)
	}
	if _, err := c.ListConversations(ConversationListOptions{}); err != nil || s.query != "" {
		t.Errorf("no filter: %v, query %q", err, s.query)
	}
}

func TestListConversationsReportsAFailure(t *testing.T) {
	c := (&conversationServer{}).start(t, 500, `{"message":"down"}`)
	if _, err := c.ListConversations(ConversationListOptions{}); err == nil {
		t.Error("expected an error")
	}
}

func TestGetConversationReadsTheData(t *testing.T) {
	s := &conversationServer{}
	c := s.start(t, 200, oneConversation)

	got, err := c.GetConversation("c 1")

	if err != nil || got.ID != "c1" || !got.Can("terminal") {
		t.Fatalf("got %+v, %v", got, err)
	}
	if s.path != "/api/conversations/c%201" {
		t.Errorf("path = %s", s.path)
	}
}

func TestConversationCallsReportWhatTheServerAnswersWrongly(t *testing.T) {
	cases := map[string]struct {
		status int
		body   string
	}{
		"a refusal":      {403, `{"message":"no"}`},
		"not json":       {200, `nope`},
		"data of a kind": {200, `{"data":"a string"}`},
	}
	for name, tc := range cases {
		c := (&conversationServer{}).start(t, tc.status, tc.body)
		if _, err := c.GetConversation("c1"); err == nil {
			t.Errorf("%s: expected an error", name)
		}
	}
	// nothing in data is not an error: there is nothing to read
	c := (&conversationServer{}).start(t, 200, `{"data":null}`)
	if got, err := c.GetConversation("c1"); err != nil || got.ID != "" {
		t.Errorf("null data: %+v, %v", got, err)
	}
	c = (&conversationServer{}).start(t, 200, `{}`)
	if _, err := c.GetConversation("c1"); err != nil {
		t.Errorf("no data: %v", err)
	}
	c = (&conversationServer{}).start(t, 200, ``)
	if _, err := c.GetConversation("c1"); err != nil {
		t.Errorf("empty body: %v", err)
	}
}

func TestConversationEventsReturnsThePageAndWhereTheNextStarts(t *testing.T) {
	s := &conversationServer{}
	c := s.start(t, 200, `{"data":[{"seq":5,"conversationId":"c1","kind":"MESSAGE","data":{"text":"hi"}}]}`, nextCursorHeader, "9")

	page, err := c.ConversationEvents("c1", 4, 50)

	if err != nil || len(page.Events) != 1 || page.Events[0].Data["text"] != "hi" || page.Next != 9 {
		t.Fatalf("page %+v, %v", page, err)
	}
	if s.query != "after=4&limit=50" {
		t.Errorf("query = %s", s.query)
	}
	c = s.start(t, 200, `{"data":[]}`)
	page, err = c.ConversationEvents("c1", 0, 0)
	if err != nil || page.Next != 0 || s.query != "after=0" {
		t.Errorf("last page: %+v, %v, query %s", page, err, s.query)
	}
}

func TestConversationEventsReportsWhatIsWrong(t *testing.T) {
	if _, err := (&conversationServer{}).start(t, 404, `{}`).ConversationEvents("c1", 0, 0); err == nil {
		t.Error("a 404 must be an error")
	}
	if _, err := (&conversationServer{}).start(t, 200, `nope`).ConversationEvents("c1", 0, 0); err == nil {
		t.Error("bad JSON must be an error")
	}
	bad := (&conversationServer{}).start(t, 200, `{"data":[]}`, nextCursorHeader, "soon")
	if _, err := bad.ConversationEvents("c1", 0, 0); err == nil || !strings.Contains(err.Error(), nextCursorHeader) {
		t.Errorf("bad cursor: %v", err)
	}
	broken := NewClient("http://[::1")
	if _, err := broken.ConversationEvents("c1", 0, 0); err == nil {
		t.Error("a bad server address must be an error")
	}
	if err := broken.InterruptConversation("c1"); err == nil {
		t.Error("a bad server address must be an error")
	}
}

func TestSendInterruptEndAndTerminalCallsPostTheirBody(t *testing.T) {
	s := &conversationServer{}
	c := s.start(t, 202, `{"data":{"queued":true}}`)

	queued, err := c.SendConversationMessage("c1", "go on", true)
	if err != nil || !queued || s.method != "POST" || s.path != "/api/conversations/c1/messages" || s.body != `{"text":"go on","interrupt":true}` {
		t.Errorf("send: %v %v %s %s %s", queued, err, s.method, s.path, s.body)
	}
	if err := c.InterruptConversation("c1"); err != nil || s.path != "/api/conversations/c1/interrupt" {
		t.Errorf("interrupt: %v %s", err, s.path)
	}
	if err := c.EndConversation("c1", "failure", "slow"); err != nil || s.body != `{"outcome":"failure","reason":"slow"}` {
		t.Errorf("end: %v %s", err, s.body)
	}
	if err := c.TerminalInput("c1", []byte("ls\r")); err != nil || s.path != "/api/conversations/c1/terminal/input" || s.body != `{"data":"bHMN"}` {
		t.Errorf("input: %v %s %s", err, s.path, s.body)
	}
	if err := c.ResizeTerminal("c1", 100, 30); err != nil || s.body != `{"cols":100,"rows":30}` {
		t.Errorf("resize: %v %s", err, s.body)
	}
}

func TestSendInterruptEndAndTerminalCallsReportARefusal(t *testing.T) {
	c := (&conversationServer{}).start(t, 403, `{"message":"not in the steer audience"}`)
	if _, err := c.SendConversationMessage("c1", "x", false); err == nil {
		t.Error("send: expected an error")
	}
	if err := c.EndConversation("c1", "", ""); err == nil {
		t.Error("end: expected an error")
	}
	if err := c.TerminalInput("c1", []byte("x")); err == nil {
		t.Error("input: expected an error")
	}
	if err := c.ResizeTerminal("c1", 1, 1); err == nil {
		t.Error("resize: expected an error")
	}
}

func TestStreamConversationsDeliversBothKindsAndResumes(t *testing.T) {
	var lastID, query string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.HasPrefix(r.URL.Path, "/api/executions/e1") && !strings.HasSuffix(r.URL.Path, "/stream") {
			_, _ = io.WriteString(w, `{"data":{"status":"SUCCESS"}}`)
			return
		}
		lastID, query = r.Header.Get("Last-Event-ID"), r.URL.RawQuery
		w.Header().Set("Content-Type", "text/event-stream")
		_, _ = io.WriteString(w, "id: 1\nevent: conversation\ndata: {\"id\":\"c1\"}\n\n"+
			"id: 2\nevent: event\ndata: {\"seq\":2,\"kind\":\"NOTICE\"}\n\n"+
			"id: 3\nevent: other\ndata: {}\n\n")
	}))
	t.Cleanup(server.Close)
	c := NewClient(server.URL)
	follow := &ConversationFollow{ExecutionID: "e1", ConversationID: "c1", Terminal: true, LastEventID: "0"}
	var conversations, events int

	err := c.StreamConversations(context.Background(), follow,
		func(Conversation) error { conversations++; return nil },
		func(ConversationEvent) error { events++; return nil })

	if err != nil || conversations != 1 || events != 1 {
		t.Fatalf("err %v, %d conversations, %d events", err, conversations, events)
	}
	if lastID != "0" || follow.LastEventID != "3" || !strings.Contains(query, "conversationId=c1") || !strings.Contains(query, "terminal=true") {
		t.Errorf("Last-Event-ID %q, follow %q, query %q", lastID, follow.LastEventID, query)
	}
}

func TestStreamConversationsRefusesNoRunAndReportsBadData(t *testing.T) {
	c := NewClient("http://localhost:9858")
	if err := c.StreamConversations(context.Background(), nil, nil, nil); err == nil {
		t.Error("nil follow")
	}
	if err := c.StreamConversations(context.Background(), &ConversationFollow{}, nil, nil); err == nil {
		t.Error("no run")
	}
	for name, body := range map[string]string{
		"conversation": "event: conversation\ndata: nope\n\n",
		"event":        "event: event\ndata: nope\n\n",
	} {
		server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
			w.Header().Set("Content-Type", "text/event-stream")
			_, _ = io.WriteString(w, body)
		}))
		defer server.Close()
		err := NewClient(server.URL).StreamConversations(context.Background(), &ConversationFollow{ExecutionID: "e1"},
			func(Conversation) error { return nil }, func(ConversationEvent) error { return nil })
		if err == nil || !strings.Contains(err.Error(), "unmarshal "+name) {
			t.Errorf("%s: %v", name, err)
		}
	}
}

func TestStreamConversationsStopsWhenTheHandlerFails(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "text/event-stream")
		_, _ = io.WriteString(w, "event: conversation\ndata: {\"id\":\"c1\"}\n\n")
	}))
	defer server.Close()
	boom := errors.New("boom")

	err := NewClient(server.URL).StreamConversations(context.Background(), &ConversationFollow{ExecutionID: "e1"},
		func(Conversation) error { return boom }, nil)

	if !errors.Is(err, boom) {
		t.Errorf("err = %v", err)
	}
}
