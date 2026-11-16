// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package conversations

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"

	"com.infenia.yukta/go-cli/internal/client"
	"com.infenia.yukta/go-cli/internal/testutil"
	"github.com/spf13/cobra"
)

const (
	conv1 = `{"id":"c1","executionId":"e1","nodeId":"review","harness":"claude-code","protocol":"JSONL","state":"RUNNING","capabilities":["message"],"seq":1}`
	conv2 = `{"id":"c2","executionId":"e2","nodeId":"fix","harness":"codex","protocol":"JSONL","state":"WAITING","capabilities":["message"],"seq":2}`
)

func sse(id int, event, data string) string {
	return fmt.Sprintf("id: %d\nevent: %s\ndata: %s\n\n", id, event, data)
}

func message(conversation string, seq int, item, text string, more bool) string {
	quoted, _ := json.Marshal(text)
	return sse(seq, "event", fmt.Sprintf(
		`{"seq":%d,"conversationId":%q,"kind":"MESSAGE","itemId":%q,"data":{"role":"agent","text":%s,"more":%t}}`,
		seq, conversation, item, quoted, more))
}

// server answers the endpoints the commands use and records the requests with their bodies.
type server struct {
	t        *testing.T
	mu       sync.Mutex
	requests []string
	streams  []string // the body of the first, second... connection
	lastIDs  []string // Last-Event-ID of each connection
	done     func() bool
}

func (s *server) client() *client.Client {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		s.mu.Lock()
		defer s.mu.Unlock()
		switch {
		case strings.HasSuffix(r.URL.Path, "/conversations/stream"):
			n := len(s.lastIDs)
			s.lastIDs = append(s.lastIDs, r.Header.Get("Last-Event-ID"))
			w.Header().Set("Content-Type", "text/event-stream")
			if n < len(s.streams) {
				_, _ = io.WriteString(w, s.streams[n])
			}
			return
		case r.Method == "GET" && strings.HasPrefix(r.URL.Path, "/api/executions/") && !strings.Contains(r.URL.Path, "/inputs"):
			status := "RUNNING"
			if s.done != nil && s.done() {
				status = "SUCCESS"
			}
			_, _ = fmt.Fprintf(w, `{"data":{"status":%q}}`, status)
			return
		}
		s.requests = append(s.requests, r.Method+" "+r.URL.RequestURI()+" "+string(body))
		w.Header().Set("Content-Type", "application/json")
		switch {
		case r.URL.Path == "/api/conversations":
			_, _ = io.WriteString(w, `{"data":[`+conv1+`,`+conv2+`]}`)
		case strings.HasSuffix(r.URL.Path, "/events"):
			w.Header().Set("X-Next-Cursor", "7")
			_, _ = io.WriteString(w, `{"data":[{"seq":5,"conversationId":"c1","kind":"MESSAGE","itemId":"m","data":{"role":"agent","text":"hello\u001b[31m red","more":false}}]}`)
		case strings.HasSuffix(r.URL.Path, "/messages"):
			w.WriteHeader(http.StatusAccepted)
			_, _ = io.WriteString(w, `{"data":{"queued":true}}`)
		case strings.HasSuffix(r.URL.Path, "/answer"):
			_, _ = io.WriteString(w, `{"data":{"id":"r1","state":"ANSWERED"}}`)
		case strings.HasPrefix(r.URL.Path, "/api/conversations/"):
			_, _ = io.WriteString(w, `{"data":`+conv1+`}`)
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	s.t.Cleanup(srv.Close)
	c := client.NewClient(srv.URL)
	c.StreamWait = 1
	return c
}

func run(t *testing.T, cmd *cobra.Command, args ...string) (string, error) {
	t.Helper()
	var out bytes.Buffer
	cmd.SetOut(&out)
	cmd.SetErr(io.Discard)
	cmd.SetIn(strings.NewReader(""))
	cmd.SetArgs(args)
	err := cmd.Execute()
	return out.String(), err
}

func TestListShowSendInterruptEnd(t *testing.T) {
	s := &server{t: t}
	c := s.client()

	var err error
	out := testutil.Capture(t, func() {
		_, err = run(t, ConversationsCmd(c), "list", "--execution", "e1", "--state", "RUNNING")
	})
	if err != nil || !strings.Contains(out, "claude-code") || !strings.Contains(out, "codex") {
		t.Fatalf("list: %v\n%s", err, out)
	}
	out, err = run(t, ConversationsCmd(c), "show", "c1", "--after", "3")
	if err != nil || !strings.Contains(out, "hello red") || strings.Contains(out, "\x1b") || !strings.Contains(out, "more: --after 7") {
		t.Fatalf("show: %v\n%q", err, out)
	}
	out, err = run(t, ConversationsCmd(c), "send", "c1", "do it", "--interrupt")
	if err != nil || !strings.Contains(out, "queued") {
		t.Fatalf("send: %v\n%s", err, out)
	}
	if _, err = run(t, ConversationsCmd(c), "interrupt", "c1"); err != nil {
		t.Fatal(err)
	}
	if _, err = run(t, ConversationsCmd(c), "end", "c1", "--outcome", "failure", "--reason", "slow"); err != nil {
		t.Fatal(err)
	}
	if _, err = run(t, ConversationsCmd(c), "end", "c1", "--outcome", "bogus"); err == nil {
		t.Fatal("a bad outcome must be refused")
	}

	want := []string{
		"GET /api/conversations?executionId=e1&state=RUNNING ",
		"GET /api/conversations/c1 ",
		"GET /api/conversations/c1/events?after=3 ",
		`POST /api/conversations/c1/messages {"text":"do it","interrupt":true}`,
		"POST /api/conversations/c1/interrupt ",
		`POST /api/conversations/c1/end {"outcome":"failure","reason":"slow"}`,
	}
	if len(s.requests) != len(want) {
		t.Fatalf("requests = %q", s.requests)
	}
	for i, w := range want {
		if s.requests[i] != w {
			t.Errorf("request %d = %q, want %q", i, s.requests[i], w)
		}
	}
}

func TestAttachMergesTheAgentsOfARunWithoutControlSequencesOrPrompt(t *testing.T) {
	s := &server{t: t, done: func() bool { return true }}
	s.streams = []string{
		sse(1, "conversation", conv1) + sse(2, "conversation", conv2) +
			message("c1", 3, "m1", "first ", true) + message("c2", 4, "m2", "from the second \x1b[2J agent", false) +
			message("c1", 5, "m1", "piece", false) +
			sse(6, "event", `{"seq":6,"conversationId":"c2","kind":"REQUEST","itemId":"r1","data":{"inputRequestId":"r1","state":"open","prompt":"Run rm?"}}`),
	}

	out, err := run(t, AttachCmd(s.client()), "e1")
	if err != nil {
		t.Fatal(err)
	}

	for _, want := range []string{
		"[review claude-code] state RUNNING",
		"[fix codex] state WAITING",
		"[fix codex] from the second  agent",
		"[review claude-code] first piece",
		"[fix codex] request r1 (open): Run rm?",
	} {
		if !strings.Contains(out, want) {
			t.Errorf("missing %q in:\n%s", want, out)
		}
	}
	if strings.Contains(out, "\x1b") || strings.Contains(out, "> ") {
		t.Errorf("piped output has a control sequence or a prompt:\n%q", out)
	}
	if strings.Index(out, "from the second") > strings.Index(out, "first piece") {
		t.Errorf("the lines are not in the order of the stream:\n%s", out)
	}
}

func TestAttachPicksUpAfterADroppedStreamWithoutRepeating(t *testing.T) {
	calls := 0
	s := &server{t: t}
	s.done = func() bool { calls++; return calls > 1 } // the run ends after the second connection
	s.streams = []string{
		sse(1, "conversation", conv1) + message("c1", 2, "m1", "before the drop", false),
		message("c1", 3, "m2", "after the drop", false),
	}

	out, err := run(t, AttachCmd(s.client()), "e1")
	if err != nil {
		t.Fatal(err)
	}

	if len(s.lastIDs) != 2 || s.lastIDs[0] != "" || s.lastIDs[1] != "2" {
		t.Fatalf("Last-Event-ID of the connections = %q, want [\"\" \"2\"]", s.lastIDs)
	}
	if strings.Count(out, "before the drop") != 1 || strings.Count(out, "after the drop") != 1 {
		t.Errorf("a line is missing or repeated:\n%s", out)
	}
}

func TestAttachJSONPrintsOneObjectPerLine(t *testing.T) {
	s := &server{t: t, done: func() bool { return true }}
	s.streams = []string{sse(1, "conversation", conv1) + message("c1", 2, "m", "hi", false)}

	out, err := run(t, AttachCmd(s.client()), "e1", "--json")
	if err != nil {
		t.Fatal(err)
	}

	lines := strings.Split(strings.TrimSpace(out), "\n")
	if len(lines) != 2 {
		t.Fatalf("lines = %q", lines)
	}
	for i, kind := range []string{"conversation", "event"} {
		var parsed map[string]json.RawMessage
		if err := json.Unmarshal([]byte(lines[i]), &parsed); err != nil || string(parsed["type"]) != `"`+kind+`"` {
			t.Errorf("line %d = %s (%v)", i, lines[i], err)
		}
	}
}

func newAttacher(c *client.Client) *attacher {
	a := &attacher{c: c, out: io.Discard, byID: map[string]*agentView{}}
	_ = a.onConversation(client.Conversation{ID: "c1", ExecutionID: "e1", NodeID: "review", Harness: "claude-code", State: "RUNNING"})
	_ = a.onConversation(client.Conversation{ID: "c2", ExecutionID: "e2", NodeID: "fix", Harness: "codex", State: "WAITING"})
	return a
}

func TestTypedLinesGoToTheSelectedAgentAndApproveAnswersItsRequest(t *testing.T) {
	s := &server{t: t}
	a := newAttacher(s.client())

	if got := a.handle("hello"); !strings.Contains(got, "no agent is selected") {
		t.Errorf("with two agents and none selected: %q", got)
	}
	if got := a.handle("/agent fix"); got != "selected fix" {
		t.Errorf("select: %q", got)
	}
	if got := a.handle("please continue"); !strings.Contains(got, "queued") {
		t.Errorf("send: %q", got)
	}
	_ = a.onEvent(client.ConversationEvent{ConversationID: "c2", Kind: "REQUEST", ItemID: "r1",
		Data: map[string]any{"inputRequestId": "r1", "state": "open", "prompt": "ok?"}})
	if got := a.handle("/approve r1 fine by me"); got != "approved request r1" {
		t.Errorf("approve: %q", got)
	}
	// no event closed the request, so it is still the one the agent waits on
	if got := a.handle("/reject"); got != "rejected request r1" {
		t.Errorf("reject: %q", got)
	}
	_ = a.handle("/interrupt")
	_ = a.handle("/end")

	joined := strings.Join(s.requests, "\n")
	for _, want := range []string{
		`POST /api/conversations/c2/messages {"text":"please continue"}`,
		`POST /api/executions/e2/inputs/r1/answer {"decision":"approve","rationale":"fine by me"}`,
		"POST /api/conversations/c2/interrupt",
		"POST /api/conversations/c2/end",
	} {
		if !strings.Contains(joined, want) {
			t.Errorf("missing request %q in:\n%s", want, joined)
		}
	}
	if list := a.handle("/agents"); !strings.Contains(list, "* 2") || !strings.Contains(list, "waits on request r1") {
		t.Errorf("/agents: %q", list)
	}
}

func TestACommandWithoutAnAgentOrWithAnUnknownNameIsExplained(t *testing.T) {
	a := newAttacher(client.NewClient("http://127.0.0.1:1"))
	if got := a.handle("/agent 9"); got != "no agent number 9" {
		t.Errorf("%q", got)
	}
	if got := a.handle("/bogus"); !strings.Contains(got, "unknown command") {
		t.Errorf("%q", got)
	}
	if got := a.handle("/term"); !strings.Contains(got, "not available") {
		t.Errorf("%q", got)
	}
}
