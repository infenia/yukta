// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package conversations

import (
	"bytes"
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"sync"
	"testing"
	"time"

	"com.infenia.yukta/go-cli/internal/client"
	"com.infenia.yukta/go-cli/internal/commands"
	"com.infenia.yukta/go-cli/internal/testutil"
)

func TestEveryCommandReportsAFailingServer(t *testing.T) {
	c, _ := testutil.Server(t, 500, `{"message":"down"}`)
	for _, args := range [][]string{
		{"show", "c1"},
		{"send", "c1", "x"},
		{"interrupt", "c1"},
		{"end", "c1"},
	} {
		if _, err := run(t, ConversationsCmd(c), args...); err == nil {
			t.Errorf("%v: expected an error", args)
		}
	}
	var err error
	testutil.Capture(t, func() { _, err = run(t, ConversationsCmd(c), "list") })
	if err == nil {
		t.Error("list: expected an error")
	}
}

func TestShowReportsAFailingEventsCall(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.HasSuffix(r.URL.Path, "/events") {
			w.WriteHeader(500)
			return
		}
		_, _ = io.WriteString(w, `{"data":`+conv1+`}`)
	}))
	t.Cleanup(srv.Close)
	if _, err := run(t, ConversationsCmd(client.NewClient(srv.URL)), "show", "c1"); err == nil {
		t.Error("expected an error")
	}
}

func TestListAndShowPrintJSONWhenAsked(t *testing.T) {
	s := &server{t: t}
	c := s.client()
	commands.SetTestOutputFormat("json")
	t.Cleanup(func() { commands.SetTestOutputFormat("table") })

	var err error
	listed := testutil.Capture(t, func() { _, err = run(t, ConversationsCmd(c), "list") })
	if err != nil || !strings.Contains(listed, `"id": "c1"`) {
		t.Errorf("list: %v %s", err, listed)
	}
	shown := testutil.Capture(t, func() { _, err = run(t, ConversationsCmd(c), "show", "c1") })
	if err != nil || !strings.Contains(shown, `"events"`) || !strings.Contains(shown, `"next": 7`) {
		t.Errorf("show: %v %s", err, shown)
	}
}

func TestSendSaysWhenTheMessageWasNotQueued(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_, _ = io.WriteString(w, `{"data":{"queued":false}}`)
	}))
	t.Cleanup(srv.Close)
	out, err := run(t, ConversationsCmd(client.NewClient(srv.URL)), "send", "c1", "hi")
	if err != nil || !strings.Contains(out, "message sent") {
		t.Errorf("%v %q", err, out)
	}
}

func TestEventsOfEveryKindBecomeLines(t *testing.T) {
	r := newRenderer()
	event := func(kind, item string, data map[string]any) client.ConversationEvent {
		return client.ConversationEvent{Kind: kind, ItemID: item, Data: data}
	}
	cases := []struct {
		event client.ConversationEvent
		want  string
	}{
		{event("MESSAGE", "", map[string]any{"role": "agent", "text": "no item"}), "no item"},
		{event("MESSAGE", "u", map[string]any{"role": "user", "text": "from ann", "author": "key:ann"}), "you (key:ann): from ann"},
		{event("MESSAGE", "p", map[string]any{"role": "user", "text": "the prompt", "author": "prompt"}), "you: the prompt"},
		{event("MESSAGE", "t", map[string]any{"role": "thought", "text": "hmm"}), "(thinking) hmm"},
		{event("TOOL", "x", map[string]any{"title": "ls", "status": "running"}), "tool ls [running]"},
		{event("PLAN", "plan", map[string]any{}), "plan updated"},
		{event("RESULT", "", map[string]any{"status": "failure", "reason": "boom"}), "result failure: boom"},
		{event("RESULT", "", map[string]any{"status": "success"}), "result success"},
		{event("NOTICE", "", map[string]any{"level": "WARN", "text": "careful"}), "notice WARN: careful"},
		{event("STATE", "", map[string]any{"state": "IDLE"}), "state IDLE"},
	}
	for _, tc := range cases {
		got := r.lines(tc.event)
		if len(got) != 1 || got[0] != tc.want {
			t.Errorf("%s = %q, want %q", tc.event.Kind, got, tc.want)
		}
	}
	if got := r.lines(event("TERMINAL", "", map[string]any{"screen": "x"})); got != nil {
		t.Errorf("terminal output must not be printed: %q", got)
	}
	if got := r.lines(event("MESSAGE", "m", map[string]any{"text": "line one\nline two\n"})); len(got) != 2 {
		t.Errorf("a message of two lines = %q", got)
	}
}

func TestTheCleanerRemovesEscapeSequencesAndKeepsLineBreaks(t *testing.T) {
	got := clean("a\x1b[31mb\x1b[0m c\x1b]0;title\x07d\x1b(Be\x00f\ng\th")
	if got != "ab cdef\ng\th" {
		t.Errorf("clean = %q", got)
	}
}

func TestIsTerminalOnlyForACharacterDevice(t *testing.T) {
	if isTerminal(strings.NewReader("")) {
		t.Error("a reader is not a terminal")
	}
	null, err := os.Open(os.DevNull)
	if err != nil {
		t.Fatal(err)
	}
	defer null.Close()
	if !isTerminal(null) {
		t.Error("a character device counts as a terminal")
	}
	file, err := os.CreateTemp(t.TempDir(), "plain")
	if err != nil {
		t.Fatal(err)
	}
	defer file.Close()
	if isTerminal(file) {
		t.Error("a file is not a terminal")
	}
	closed, _ := os.Open(os.DevNull)
	_ = closed.Close()
	if isTerminal(closed) {
		t.Error("a closed file is not a terminal")
	}
}

func TestAttachFollowsTheAgentNamedByTheFlagAndKeepsEventsOfUnknownConversations(t *testing.T) {
	s := &server{t: t, done: func() bool { return true }}
	s.streams = []string{
		sse(1, "event", `{"seq":1,"conversationId":"early","kind":"NOTICE","data":{"level":"INFO","text":"before its conversation"}}`) +
			sse(2, "conversation", conv1) + sse(3, "conversation", conv1) +
			sse(4, "event", `{"seq":4,"conversationId":"c1","kind":"REQUEST","itemId":"r1","data":{"inputRequestId":"r1","state":"open","prompt":"p"}}`) +
			sse(5, "event", `{"seq":5,"conversationId":"c1","kind":"REQUEST","itemId":"r1","data":{"inputRequestId":"r1","state":"answered","prompt":"p"}}`) +
			sse(6, "event", `{"seq":6,"conversationId":"c1","kind":"REQUEST","itemId":"r2","data":{"inputRequestId":"r2","state":"expired","prompt":"q"}}`) +
			sse(7, "conversation", `{"id":"c1","nodeId":"review","harness":"claude-code","state":"ENDED","outcome":"success","reason":"done"}`),
	}
	a := &attacher{c: s.client(), out: &bytes.Buffer{}, filter: "review", byID: map[string]*agentView{}}

	if err := a.run(context.Background(), "e1", newLines(strings.NewReader(""))); err != nil {
		t.Fatal(err)
	}

	out := a.out.(*bytes.Buffer).String()
	if !strings.Contains(out, "before its conversation") || !strings.Contains(out, "state ENDED (success): done") {
		t.Errorf("output:\n%s", out)
	}
	if a.selected != "c1" || a.byID["c1"].openRequest != "" {
		t.Errorf("selected %q, open request %q", a.selected, a.byID["c1"].openRequest)
	}
	if strings.Count(out, "state RUNNING") != 1 {
		t.Errorf("a repeated state is printed once:\n%s", out)
	}
}

func TestAttachReadsTheTypedLinesOfAPersonAndLeavesOnQuit(t *testing.T) {
	var (
		postedMu sync.Mutex
		posted   []string
	)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case strings.HasSuffix(r.URL.Path, "/conversations/stream"):
			w.Header().Set("Content-Type", "text/event-stream")
			_, _ = io.WriteString(w, sse(1, "conversation", conv1))
			w.(http.Flusher).Flush()
			<-r.Context().Done() // the run goes on until the person leaves
		case r.Method == "POST":
			body, _ := io.ReadAll(r.Body)
			postedMu.Lock()
			posted = append(posted, r.URL.Path+" "+string(body))
			postedMu.Unlock()
			_, _ = io.WriteString(w, `{"data":{"queued":false}}`)
		default:
			_, _ = io.WriteString(w, `{"data":{"status":"RUNNING"}}`)
		}
	}))
	t.Cleanup(srv.Close)
	var out safeBuffer
	a := &attacher{c: client.NewClient(srv.URL), out: &out, interactive: true, byID: map[string]*agentView{}}
	reader, writer := io.Pipe()
	done := make(chan error, 1)
	go func() { done <- a.run(context.Background(), "e1", newLines(reader)) }()
	// the person types once the agent is on the screen
	for i := 0; i < 500 && !strings.Contains(out.String(), "state RUNNING"); i++ {
		time.Sleep(10 * time.Millisecond)
	}
	_, _ = io.WriteString(writer, "\nhello there\n/agents\n/agent 1\n/quit\n")
	select {
	case err := <-done:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("attach did not leave on /quit")
	}

	postedMu.Lock()
	defer postedMu.Unlock()
	if len(posted) != 1 || posted[0] != `/api/conversations/c1/messages {"text":"hello there"}` {
		t.Errorf("posted %q", posted)
	}
	if !strings.Contains(out.String(), "* 1  c1  review claude-code  RUNNING") {
		t.Errorf("output:\n%s", out.String())
	}
}

func TestACommandReportsWhatTheServerRefuses(t *testing.T) {
	c, _ := testutil.Server(t, 403, `{"message":"not in the steer audience"}`)
	a := newAttacher(c)
	_ = a.handle("/agent review")
	_ = a.onEvent(client.ConversationEvent{ConversationID: "c1", Kind: "REQUEST", ItemID: "r1",
		Data: map[string]any{"inputRequestId": "r1", "state": "open"}})
	for line, want := range map[string]string{
		"hello":      "not sent: ",
		"/interrupt": "not interrupted: ",
		"/end":       "not ended: ",
		"/approve":   "not answered: ",
	} {
		if got := a.handle(line); !strings.HasPrefix(got, want) {
			t.Errorf("%s = %q, want %q...", line, got, want)
		}
	}
	if got := a.handle("/agent"); !strings.HasPrefix(got, "usage") {
		t.Errorf("/agent alone = %q", got)
	}
	if got := a.handle("/agent nobody"); got != "no agent nobody" {
		t.Errorf("/agent nobody = %q", got)
	}
	if got := a.handle("/agent 1"); got != "selected review" {
		t.Errorf("/agent 1 = %q", got)
	}
	if got := a.handle("/agent c2"); got != "selected fix" {
		t.Errorf("/agent c2 = %q", got)
	}
}

func TestTheOnlyAgentThatIsNotOverIsTheDefault(t *testing.T) {
	c, _ := testutil.Server(t, 200, `{"data":{"queued":false}}`)
	a := &attacher{c: c, out: io.Discard, byID: map[string]*agentView{}}
	if got := a.handle("/agents"); got != "no agents yet" {
		t.Errorf("/agents = %q", got)
	}
	_ = a.onConversation(client.Conversation{ID: "c1", NodeID: "n", State: "ENDED"})
	_ = a.onConversation(client.Conversation{ID: "c2", NodeID: "m", State: "IDLE"})

	if got := a.handle("hello"); got != "" || a.selected != "c2" {
		t.Errorf("handle = %q, selected %q", got, a.selected)
	}
	if got := a.handle("/approve"); got != "the agent waits on no request" {
		t.Errorf("/approve = %q", got)
	}
}

func TestTellPrintsAndStaysQuietForNothing(t *testing.T) {
	var out bytes.Buffer
	a := &attacher{out: &out}
	a.tell("")
	a.tell("done")
	if out.String() != "* done\n" {
		t.Errorf("out = %q", out.String())
	}
}

func TestJSONLinesCarryTheKindOfWhatTheyHold(t *testing.T) {
	var out bytes.Buffer
	a := &attacher{out: &out, jsonLines: true, byID: map[string]*agentView{}}
	if err := a.printJSON("event", make(chan int)); err == nil {
		t.Error("a value that cannot be encoded must be an error")
	}
}

// safeBuffer is a buffer the test reads while attach writes to it.
type safeBuffer struct {
	mu  sync.Mutex
	buf bytes.Buffer
}

func (b *safeBuffer) Write(p []byte) (int, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.buf.Write(p)
}

func (b *safeBuffer) String() string {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.buf.String()
}
