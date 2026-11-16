// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package conversations

import (
	"bytes"
	"context"
	"encoding/base64"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"com.infenia.yukta/go-cli/internal/client"
)

// terminalServer plays the endpoints of a terminal agent and records the posts.
type terminalServer struct {
	mu      sync.Mutex
	posts   []string
	failing bool
	stream  string
	query   string
}

func (s *terminalServer) client(t *testing.T) *client.Client {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		switch {
		case strings.HasSuffix(r.URL.Path, "/conversations/stream"):
			s.mu.Lock()
			s.query = r.URL.RawQuery
			stream, failing := s.stream, s.failing
			s.mu.Unlock()
			w.Header().Set("Content-Type", "text/event-stream")
			_, _ = io.WriteString(w, stream)
			if failing {
				return
			}
			w.(http.Flusher).Flush()
			<-r.Context().Done() // the lock is not held while the stream stays open
		case r.Method == "POST":
			s.mu.Lock()
			s.posts = append(s.posts, r.URL.Path+" "+string(body))
			failing := s.failing
			s.mu.Unlock()
			if failing {
				w.WriteHeader(http.StatusForbidden)
			}
			_, _ = io.WriteString(w, `{"data":null}`)
		default:
			_, _ = io.WriteString(w, `{"data":{"status":"RUNNING"}}`)
		}
	}))
	t.Cleanup(srv.Close)
	c := client.NewClient(srv.URL)
	c.StreamWait = 1
	return c
}

func (s *terminalServer) seen() string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return strings.Join(s.posts, "\n")
}

func (s *terminalServer) streamQuery() string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.query
}

var ptyAgent = client.Conversation{ID: "c1", RootExecutionID: "e1", ExecutionID: "e1", NodeID: "build", Harness: "terminal", Capabilities: []string{"terminal"}, State: "RUNNING"}

func termOutput(text string) string {
	return sse(1, "event", fmt.Sprintf(`{"seq":1,"conversationId":"c1","kind":"TERMINAL","data":{"data":%q}}`, base64.StdEncoding.EncodeToString([]byte(text))))
}

type syncBuffer struct {
	mu  sync.Mutex
	buf bytes.Buffer
}

func (b *syncBuffer) Write(p []byte) (int, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.buf.Write(p)
}

func (b *syncBuffer) String() string {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.buf.String()
}

func waitFor(t *testing.T, what string, ok func() bool) {
	t.Helper()
	for i := 0; i < 300; i++ {
		if ok() {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("timed out waiting for %s", what)
}

func TestPassThroughShowsTheOutputSendsTheKeysAndLeavesOnCtrlBracket(t *testing.T) {
	s := &terminalServer{stream: sse(1, "conversation", conv1) + termOutput("hello\r\n") +
		sse(2, "event", `{"seq":2,"conversationId":"c1","kind":"TERMINAL","data":{"screen":"one\ntwo"}}`) +
		sse(3, "event", `{"seq":3,"conversationId":"c1","kind":"TERMINAL","data":{"data":"%%%"}}`) +
		sse(4, "event", `{"seq":4,"conversationId":"c1","kind":"TERMINAL","data":{"cols":80}}`) +
		sse(5, "event", `{"seq":5,"conversationId":"c1","kind":"MESSAGE","data":{"text":"x"}}`)}
	in, writer := io.Pipe()
	var out syncBuffer
	resized := make(chan struct{}, 1)
	done := make(chan error, 1)
	go func() {
		done <- passThrough(context.Background(), s.client(t), ptyAgent, terminalIO{
			in: in, out: &out, resized: resized,
			size: func() (int, int, error) { return 100, 30, nil },
		})
	}()

	waitFor(t, "the output", func() bool {
		return strings.Contains(out.String(), "hello") && strings.Contains(out.String(), "one\r\ntwo")
	})
	_, _ = writer.Write([]byte("ls\r"))
	waitFor(t, "the keys", func() bool { return strings.Contains(s.seen(), `{"data":"bHMN"}`) })
	resized <- struct{}{}
	waitFor(t, "a second size", func() bool { return strings.Count(s.seen(), "/terminal/resize") == 2 })
	_, _ = writer.Write([]byte("ab\x1dcd"))

	if err := <-done; err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(out.String(), "\x1b[2J\x1b[H") || strings.Contains(out.String(), "%%%") {
		t.Errorf("output = %q", out.String())
	}
	seen := s.seen()
	if !strings.Contains(seen, `{"data":"YWI="}`) || strings.Contains(seen, "Y2Q=") {
		t.Errorf("only what came before Ctrl-] is sent:\n%s", seen)
	}
	if !strings.Contains(seen, `/api/conversations/c1/terminal/resize {"cols":100,"rows":30}`) {
		t.Errorf("the size is sent:\n%s", seen)
	}
	if !strings.Contains(s.streamQuery(), "terminal=true") || !strings.Contains(s.streamQuery(), "conversationId=c1") {
		t.Errorf("query = %q", s.streamQuery())
	}
}

func TestPassThroughEndsWhenTheInputEnds(t *testing.T) {
	s := &terminalServer{}
	err := passThrough(context.Background(), s.client(t), ptyAgent, terminalIO{
		in: strings.NewReader("x"), out: io.Discard,
		size: func() (int, int, error) { return 0, 0, errors.New("no size") },
	})
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(s.seen(), "resize") || !strings.Contains(s.seen(), "terminal/input") {
		t.Errorf("posts:\n%s", s.seen())
	}
}

func TestPassThroughReportsAServerThatRefusesTheKeysOrTheSize(t *testing.T) {
	s := &terminalServer{failing: true}
	err := passThrough(context.Background(), s.client(t), ptyAgent, terminalIO{
		in: strings.NewReader("x"), out: io.Discard,
		size: func() (int, int, error) { return 80, 24, nil },
	})
	if err == nil {
		t.Fatal("a refused key must be an error")
	}

	// a refused size is reported at the next read
	in, writer := io.Pipe()
	done := make(chan error, 1)
	go func() {
		done <- passThrough(context.Background(), s.client(t), ptyAgent, terminalIO{
			in: in, out: io.Discard, size: func() (int, int, error) { return 80, 24, nil },
		})
	}()
	time.Sleep(200 * time.Millisecond)
	_ = writer.Close()
	<-done
}

func TestPassThroughReportsAStreamThatEnds(t *testing.T) {
	s := &terminalServer{failing: true}
	in, writer := io.Pipe()
	done := make(chan error, 1)
	go func() {
		done <- passThrough(context.Background(), s.client(t), ptyAgent, terminalIO{
			in: in, out: io.Discard, size: func() (int, int, error) { return 0, 0, errors.New("x") },
		})
	}()
	time.Sleep(300 * time.Millisecond)
	_, _ = writer.Write([]byte("k"))

	if err := <-done; err == nil {
		t.Error("expected an error")
	}
}

func TestDrawWritesOutputAndScreensAndIgnoresTheRest(t *testing.T) {
	var out bytes.Buffer
	if err := draw(&out, client.ConversationEvent{Kind: "NOTICE"}); err != nil || out.Len() != 0 {
		t.Errorf("a notice is not drawn: %v %q", err, out.String())
	}
	_ = draw(&out, client.ConversationEvent{Kind: "TERMINAL", Data: map[string]any{"cols": 80}})
	if out.Len() != 0 {
		t.Errorf("nothing to draw: %q", out.String())
	}
	if err := draw(failing{}, client.ConversationEvent{Kind: "TERMINAL", Data: map[string]any{"screen": "x"}}); err == nil {
		t.Error("a failed write must be an error")
	}
	if err := draw(failing{}, client.ConversationEvent{Kind: "TERMINAL", Data: map[string]any{"data": "AAAA"}}); err == nil {
		t.Error("a failed write must be an error")
	}
}

type failing struct{}

func (failing) Write([]byte) (int, error) { return 0, errors.New("closed") }

func TestScannerLinesReadsLinesAndSaysWhenItIsOver(t *testing.T) {
	lines := newLines(strings.NewReader("one\ntwo"))
	for _, want := range []string{"one", "two"} {
		if got, err := lines.ReadLine(); err != nil || got != want {
			t.Errorf("got %q, %v", got, err)
		}
	}
	if _, err := lines.ReadLine(); !errors.Is(err, io.EOF) {
		t.Errorf("err = %v", err)
	}
	bad := newLines(errReader{})
	if _, err := bad.ReadLine(); err == nil || errors.Is(err, io.EOF) {
		t.Errorf("a read error must show: %v", err)
	}
}

type errReader struct{}

func (errReader) Read([]byte) (int, error) { return 0, errors.New("broken") }

func TestRawTerminalNeedsATerminalAndAnAgentThatHasOne(t *testing.T) {
	var out bytes.Buffer
	a := &attacher{out: &out, byID: map[string]*agentView{}}
	_ = a.onConversation(ptyAgent)
	if got := a.handle("/term"); !strings.Contains(got, "needs a terminal") {
		t.Errorf("%q", got)
	}

	a.term = func(client.Conversation) error { return nil }
	_ = a.onConversation(client.Conversation{ID: "c2", NodeID: "chat", State: "RUNNING"})
	_ = a.handle("/agent chat")
	if got := a.handle("/term"); got != "this agent has no terminal" {
		t.Errorf("%q", got)
	}
}

func TestWhatArrivesDuringTheRawTerminalIsShownAfterIt(t *testing.T) {
	var out bytes.Buffer
	a := &attacher{out: &out, byID: map[string]*agentView{}}
	_ = a.onConversation(ptyAgent)
	a.term = func(client.Conversation) error {
		out.Reset()
		_ = a.onEvent(client.ConversationEvent{ConversationID: "c1", Kind: "NOTICE", Data: map[string]any{"level": "INFO", "text": "meanwhile"}})
		if out.Len() != 0 {
			t.Errorf("printed during the raw view: %q", out.String())
		}
		return nil
	}

	if got := a.handle("/term"); got != "back from the terminal" {
		t.Errorf("%q", got)
	}
	if !strings.Contains(out.String(), "meanwhile") {
		t.Errorf("held line not shown: %q", out.String())
	}

	a.term = func(client.Conversation) error { return errors.New("403") }
	if got := a.handle("/term"); got != "left the terminal: 403" {
		t.Errorf("%q", got)
	}
}

func TestAttachStopsReadingWhenTheInputEnds(t *testing.T) {
	s := &server{t: t, done: func() bool { return true }}
	s.streams = []string{sse(1, "conversation", conv1)}
	a := &attacher{c: s.client(), out: io.Discard, interactive: true, byID: map[string]*agentView{}}

	if err := a.run(context.Background(), "e1", newLines(strings.NewReader(""))); err != nil {
		t.Fatal(err)
	}
}
