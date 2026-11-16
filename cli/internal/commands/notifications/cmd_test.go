// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package notifications

import (
	"bytes"
	"context"
	"errors"
	"fmt"
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
	"com.infenia.yukta/go-cli/internal/notify"
	"github.com/spf13/cobra"
)

const inbox = `{"data":[` +
	`{"id":"n1","kind":"ACTION","severity":"WARN","category":"input","title":"Deploy\u001b[31m now?","state":"ACTIVE","sessionId":"s1","updatedAt":"t1","read":false,"executionId":"e1","inputRequestId":"r1"},` +
	`{"id":"n2","kind":"INFO","severity":"ERROR","category":"run.failed","title":"Run failed","state":"ACTIVE","updatedAt":"t2","read":true}]}`

// run executes a command and returns what it printed to stdout.
func run(t *testing.T, cmd *cobra.Command, format string, args ...string) (string, error) {
	t.Helper()
	orig := commands.GetOutputFormat()
	commands.SetTestOutputFormat(format)
	defer commands.SetTestOutputFormat(orig)

	r, w, _ := os.Pipe()
	old := os.Stdout
	os.Stdout = w
	var out bytes.Buffer
	cmd.SetArgs(args)
	cmd.SetOut(&out)
	cmd.SetErr(io.Discard)
	err := cmd.Execute()
	_ = w.Close()
	os.Stdout = old
	var printed bytes.Buffer
	_, _ = io.Copy(&printed, r)
	return printed.String() + out.String(), err
}

func server(t *testing.T, status int, body string, seen *[]string) *client.Client {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if seen != nil {
			*seen = append(*seen, r.Method+" "+r.URL.RequestURI())
		}
		w.WriteHeader(status)
		_, _ = io.WriteString(w, body)
	}))
	t.Cleanup(srv.Close)
	return client.NewClient(srv.URL)
}

func TestList_tableAndJSONAndFilters(t *testing.T) {
	var seen []string
	c := server(t, 200, inbox, &seen)

	out, err := run(t, NotificationsCmd(c), "table", "list", "--unread", "--kind", "ACTION", "--severity", "WARN")
	if err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{"Deploy [31m now?", "Run failed", "input", "run.failed", "*"} {
		if !strings.Contains(out, want) {
			t.Errorf("table lacks %q:\n%s", want, out)
		}
	}
	if strings.Contains(out, "\x1b") {
		t.Error("control characters must not be printed")
	}
	if !strings.Contains(seen[0], "kind=ACTION") || !strings.Contains(seen[0], "size=200") {
		t.Errorf("request = %v", seen)
	}

	out, err = run(t, NotificationsCmd(c), "json", "list")
	if err != nil || !strings.Contains(out, `"id": "n1"`) {
		t.Errorf("json = %s, err = %v", out, err)
	}
}

func TestList_failure(t *testing.T) {
	_, err := run(t, NotificationsCmd(server(t, 500, `{"message":"down"}`, nil)), "table", "list")
	if err == nil || !strings.Contains(err.Error(), "failed to list the notifications") {
		t.Errorf("err = %v", err)
	}
}

func TestRead(t *testing.T) {
	var seen []string
	c := server(t, 200, `{"data":{"marked":2}}`, &seen)

	out, err := run(t, NotificationsCmd(c), "table", "read", "n1", "n2")
	if err != nil || !strings.Contains(out, "Marked 2 read") {
		t.Errorf("out = %q, err = %v", out, err)
	}
	out, err = run(t, NotificationsCmd(c), "json", "read", "--all")
	if err != nil || !strings.Contains(out, `"marked": 2`) {
		t.Errorf("out = %q, err = %v", out, err)
	}
	if _, err := run(t, NotificationsCmd(c), "table", "read"); err == nil {
		t.Error("nothing to mark must fail")
	}
}

func TestDismiss(t *testing.T) {
	var seen []string
	out, err := run(t, NotificationsCmd(server(t, 200, "{}", &seen)), "table", "dismiss", "n1")
	if err != nil || !strings.Contains(out, "Dismissed n1") || seen[0] != "POST /api/notifications/n1/dismiss" {
		t.Errorf("out = %q, err = %v, seen = %v", out, err, seen)
	}
	_, err = run(t, NotificationsCmd(server(t, 409, `{"message":"open"}`, nil)), "table", "dismiss", "n1")
	if err == nil || !strings.Contains(err.Error(), "failed to dismiss") {
		t.Errorf("err = %v", err)
	}
}

// ---- notify ----

type buffer struct {
	mu sync.Mutex
	b  bytes.Buffer
}

func (b *buffer) Write(p []byte) (int, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.b.Write(p)
}

func (b *buffer) String() string {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.b.String()
}

func frame(event, data string) string {
	return fmt.Sprintf("id: x-%d\nevent: %s\ndata: %s\n\n", time.Now().UnixNano(), event, data)
}

// streamServer sends the frames once and answers the preferences; the stream then stays open.
func streamServer(t *testing.T, prefs string, frames ...string) *client.Client {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/api/notifications/preferences" {
			if prefs == "" {
				w.WriteHeader(500)
				return
			}
			_, _ = io.WriteString(w, prefs)
			return
		}
		w.Header().Set("Content-Type", "text/event-stream")
		for _, f := range frames {
			_, _ = io.WriteString(w, f)
		}
		w.(http.Flusher).Flush()
		<-r.Context().Done()
	}))
	t.Cleanup(srv.Close)
	return client.NewClient(srv.URL)
}

type machine struct {
	goos   string
	look   notify.LookPath
	calls  buffer
	errOut buffer
	cancel context.CancelFunc
	failOn string
}

func (m *machine) environment() environment {
	return environment{
		goos: m.goos,
		look: m.look,
		run: func(_ context.Context, env []string, name string, args ...string) (string, error) {
			fmt.Fprintf(&m.calls, "%s %s\n", name, strings.Join(args, " | "))
			if name == m.failOn {
				return "", errors.New("tool failed")
			}
			return "", nil
		},
		context: func() (context.Context, context.CancelFunc) {
			ctx, cancel := context.WithCancel(context.Background())
			m.cancel = cancel
			return ctx, cancel
		},
		errOut: func() io.Writer { return &m.errOut },
	}
}

func found(names ...string) notify.LookPath {
	return func(name string) (string, error) {
		for _, n := range names {
			if n == name {
				return name, nil
			}
		}
		return "", errors.New("missing")
	}
}

// until runs a notify command until what it printed satisfies done, then stops it.
func until(t *testing.T, c *client.Client, m *machine, done func() bool, args ...string) string {
	t.Helper()
	var out buffer
	cmd := notifyCmd(c, m.environment())
	cmd.SetOut(&out)
	cmd.SetErr(io.Discard)
	cmd.SetArgs(args)
	finished := make(chan error, 1)
	go func() { finished <- cmd.Execute() }()
	deadline := time.After(10 * time.Second)
	for !done() {
		select {
		case err := <-finished:
			t.Fatalf("ended early: %v", err)
		case <-deadline:
			t.Fatal("timeout")
		case <-time.After(5 * time.Millisecond):
		}
	}
	m.cancel()
	if err := <-finished; err != nil {
		t.Fatalf("a stop by the user must not be an error: %v", err)
	}
	return out.String()
}

const action = `{"type":"created","notification":{"id":"n1","kind":"ACTION","severity":"WARN","category":"input","title":"Deploy?","body":"to prod","sessionId":"s1","executionId":"e1","inputRequestId":"r1"}}`
const alert = `{"type":"created","notification":{"id":"n2","kind":"INFO","severity":"ERROR","category":"run.failed","title":"Run failed","sessionId":"s2"}}`

func TestNotify_raisesANativeNotificationWithTheAnswerCommand(t *testing.T) {
	c := streamServer(t, `{"data":{"levels":{},"mutedSessions":[]}}`, frame("created", action))
	m := &machine{goos: "linux", look: found("notify-send")}

	until(t, c, m, func() bool { return strings.Contains(m.calls.String(), "Deploy?") })

	calls := m.calls.String()
	if !strings.Contains(calls, "notify-send --app-name=Yukta | --urgency=normal | -- | Deploy? | to prod\nyukta inputs answer e1 r1 --decision ...") {
		t.Errorf("calls = %q", calls)
	}
}

func TestNotify_printsWhenThereIsNoNotifierOrWithPrint(t *testing.T) {
	c := streamServer(t, "", frame("created", action), frame("created", alert), frame("receipt", `{"type":"receipt"}`),
		frame("created", `{"type":"created"}`), "data: not json\n\n")
	m := &machine{goos: "linux", look: found()}
	out := until(t, c, m, func() bool { return strings.Contains(m.errOut.String(), "printing instead") }, "--min-severity", "info")
	_ = out
	if !strings.Contains(m.errOut.String(), "No desktop notifier found") {
		t.Errorf("stderr = %q", m.errOut.String())
	}

	m2 := &machine{goos: "linux", look: found("notify-send")}
	var printed buffer
	cmd := notifyCmd(c, m2.environment())
	cmd.SetOut(&printed)
	cmd.SetArgs([]string{"--print"})
	done := make(chan error, 1)
	go func() { done <- cmd.Execute() }()
	for !strings.Contains(printed.String(), "Run failed") {
		time.Sleep(5 * time.Millisecond)
	}
	m2.cancel()
	<-done
	lines := printed.String()
	if !strings.Contains(lines, "[WARN] input: Deploy?  ->  yukta inputs answer e1 r1 --decision ...") ||
		!strings.Contains(lines, "[ERROR] run.failed: Run failed") {
		t.Errorf("printed = %q", lines)
	}
	if m2.calls.String() != "" {
		t.Errorf("--print must not run a notifier: %q", m2.calls.String())
	}
}

func TestNotify_filtersAndPreferences(t *testing.T) {
	prefs := `{"data":{"levels":{"run.failed":"INBOX"},"mutedSessions":["s9"]}}`
	read := `{"type":"created","notification":{"id":"n3","kind":"INFO","severity":"ERROR","category":"input","title":"already read","read":true}}`
	muted := `{"type":"created","notification":{"id":"n4","kind":"INFO","severity":"ERROR","category":"input","title":"muted","sessionId":"s9"}}`
	last := `{"type":"created","notification":{"id":"n5","kind":"ACTION","severity":"ERROR","category":"input","title":"the one","sessionId":"s1","executionId":"e","inputRequestId":"it's"}}`
	c := streamServer(t, prefs, frame("created", action), frame("created", alert), frame("created", read), frame("created", muted),
		frame("created", last))
	m := &machine{goos: "linux", look: found()}
	var printed buffer
	cmd := notifyCmd(c, m.environment())
	cmd.SetOut(&printed)
	cmd.SetArgs([]string{"--kind", "action", "--min-severity", "ERROR", "--session", "s1"})
	done := make(chan error, 1)
	go func() { done <- cmd.Execute() }()
	for !strings.Contains(printed.String(), "the one") {
		time.Sleep(5 * time.Millisecond)
	}
	m.cancel()
	<-done

	got := printed.String()
	if strings.Count(got, "\n") != 1 || !strings.Contains(got, `yukta inputs answer e 'it'\''s' --decision ...`) {
		t.Errorf("printed = %q", got)
	}
}

func TestNotify_execRunsYourCommandAndReportsAFailure(t *testing.T) {
	c := streamServer(t, "", frame("created", action), frame("created", alert))
	m := &machine{goos: "linux", look: found(), failOn: "tmux"}

	until(t, c, m, func() bool {
		return strings.Count(m.calls.String(), "tmux") >= 2 && strings.Contains(m.errOut.String(), "Run failed")
	},
		"--exec", "tmux display-message {{.Title}}-{{.Kind}}")

	if !strings.Contains(m.calls.String(), "tmux display-message | Deploy?-ACTION") {
		t.Errorf("calls = %q", m.calls.String())
	}
	if !strings.Contains(m.errOut.String(), `Could not show "Deploy?": tool failed`) {
		t.Errorf("stderr = %q", m.errOut.String())
	}
}

func TestNotify_aFailingToolDoesNotStopTheStream(t *testing.T) {
	c := streamServer(t, "", frame("created", action), frame("created", alert))
	m := &machine{goos: "darwin", look: found("osascript"), failOn: "osascript"}

	until(t, c, m, func() bool { return strings.Count(m.errOut.String(), "Could not show") >= 2 })
}

func TestNotify_resetReadsThePreferencesAgain(t *testing.T) {
	var reads int
	var mu sync.Mutex
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/api/notifications/preferences" {
			mu.Lock()
			reads++
			mu.Unlock()
			_, _ = io.WriteString(w, `{"data":{"levels":{},"mutedSessions":[]}}`)
			return
		}
		w.Header().Set("Content-Type", "text/event-stream")
		_, _ = io.WriteString(w, frame("reset", `{"type":"reset"}`))
		w.(http.Flusher).Flush()
		<-r.Context().Done()
	}))
	t.Cleanup(srv.Close)
	m := &machine{goos: "linux", look: found()}

	until(t, client.NewClient(srv.URL), m, func() bool {
		mu.Lock()
		defer mu.Unlock()
		return reads >= 2
	})
}

func TestNotify_badArguments(t *testing.T) {
	c := client.NewClient("http://127.0.0.1:1")
	m := &machine{goos: "linux", look: found()}
	for _, args := range [][]string{{"--min-severity", "loud"}, {"--exec", `tmux "open`}, {"extra"}} {
		if _, err := run(t, notifyCmd(c, m.environment()), "table", args...); err == nil {
			t.Errorf("%v must fail", args)
		}
	}
}

func TestNotify_aRefusedKeyIsAnError(t *testing.T) {
	c := server(t, 401, `{"message":"no key"}`, nil)
	m := &machine{goos: "linux", look: found()}

	if _, err := run(t, notifyCmd(c, m.environment()), "table", "--print"); err == nil {
		t.Error("a refused key must end the command with an error")
	}
}

func TestQuote(t *testing.T) {
	for in, want := range map[string]string{"e1": "e1", "a b": "'a b'", "it's": `'it'\''s'`, "$(id)": "'$(id)'"} {
		if got := quote(in); got != want {
			t.Errorf("quote(%q) = %s, want %s", in, got, want)
		}
	}
}

func TestSystemEnvironment(t *testing.T) {
	env := systemEnvironment()
	ctx, stop := env.context()
	stop()
	if ctx.Err() == nil || env.errOut() != os.Stderr || env.look == nil || env.run == nil || env.goos == "" {
		t.Error("the system environment is not complete")
	}
	if NotifyCmd(client.NewClient("http://x")).Use == "" {
		t.Error("NotifyCmd must build the command")
	}
}
