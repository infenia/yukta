// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package ptyhost

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/aymanbagabas/go-pty"
)

// lockedBuffer is written by the host's goroutines and read by the test.
type lockedBuffer struct {
	mu  sync.Mutex
	buf bytes.Buffer
}

func (b *lockedBuffer) Write(p []byte) (int, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.buf.Write(p)
}

func (b *lockedBuffer) frames() []map[string]any {
	b.mu.Lock()
	defer b.mu.Unlock()
	var found []map[string]any
	for _, line := range strings.Split(strings.TrimSpace(b.buf.String()), "\n") {
		var frame map[string]any
		if json.Unmarshal([]byte(line), &frame) == nil {
			found = append(found, frame)
		}
	}
	return found
}

// output is everything the program wrote, decoded.
func (b *lockedBuffer) output() string {
	var all strings.Builder
	for _, frame := range b.frames() {
		if frame["t"] == "out" {
			data, _ := base64.StdEncoding.DecodeString(frame["d"].(string))
			all.Write(data)
		}
	}
	return all.String()
}

func (b *lockedBuffer) ofType(kind string) []map[string]any {
	var found []map[string]any
	for _, frame := range b.frames() {
		if frame["t"] == kind {
			found = append(found, frame)
		}
	}
	return found
}

type session struct {
	t    *testing.T
	in   *io.PipeWriter
	out  *lockedBuffer
	done chan result
}

type result struct {
	code int
	err  error
}

// start runs a program with options tuned for tests.
func start(t *testing.T, command []string, tune func(*Options)) *session {
	t.Helper()
	pr, pw := io.Pipe()
	s := &session{t: t, in: pw, out: &lockedBuffer{}, done: make(chan result, 1)}
	opts := New(command, 80, 24)
	opts.Quiet = 60 * time.Millisecond
	opts.Gather = 10 * time.Millisecond
	opts.Grace = 200 * time.Millisecond
	opts.Drain = 300 * time.Millisecond
	if tune != nil {
		tune(&opts)
	}
	go func() {
		code, err := Run(pr, s.out, opts)
		s.done <- result{code, err}
	}()
	t.Cleanup(func() { _ = pw.Close() })
	return s
}

func (s *session) send(frame string) {
	s.t.Helper()
	if _, err := io.WriteString(s.in, frame+"\n"); err != nil {
		s.t.Fatalf("writing a frame: %v", err)
	}
}

func (s *session) typed(text string) {
	s.send(`{"t":"in","d":"` + base64.StdEncoding.EncodeToString([]byte(text)) + `"}`)
}

func (s *session) wait() result {
	s.t.Helper()
	select {
	case r := <-s.done:
		return r
	case <-time.After(15 * time.Second):
		s.t.Fatal("the host did not end")
		return result{}
	}
}

func (s *session) until(what string, ok func() bool) {
	s.t.Helper()
	deadline := time.Now().Add(10 * time.Second)
	for !ok() {
		if time.Now().After(deadline) {
			s.t.Fatalf("timed out waiting for %s; output %q", what, s.out.output())
		}
		time.Sleep(10 * time.Millisecond)
	}
}

func TestEcho_typedBytesComeBackAsOutput(t *testing.T) {
	s := start(t, []string{"cat"}, nil)

	s.typed("hello\n")
	s.until("the echo", func() bool { return strings.Contains(s.out.output(), "hello") })
	s.send(`{"t":"close"}`)
	r := s.wait()

	if r.err != nil {
		t.Fatalf("Run failed: %v", r.err)
	}
	exit := s.out.ofType("exit")
	if len(exit) != 1 || exit[0]["code"].(float64) != -1 {
		t.Fatalf("want one exit frame with -1 (cat died of SIGHUP), got %v", exit)
	}
}

func TestResize_changesTheSizeTheProgramSees(t *testing.T) {
	s := start(t, []string{"sh", "-c", "stty size; read x; stty size"}, nil)

	s.until("the first size", func() bool { return strings.Contains(s.out.output(), "24 80") })
	s.send(`{"t":"resize","cols":100,"rows":30}`)
	s.typed("go\n")
	s.until("the second size", func() bool { return strings.Contains(s.out.output(), "30 100") })
	r := s.wait()

	if r.code != 0 {
		t.Fatalf("code = %d", r.code)
	}
}

func TestScreen_isSentOnceAfterQuietAndOnlyWhenItChanged(t *testing.T) {
	s := start(t, []string{"sh", "-c", "printf 'hello\\n'; sleep 1"}, nil)

	s.until("a screen frame", func() bool { return len(s.out.ofType("screen")) > 0 })
	r := s.wait()

	screens := s.out.ofType("screen")
	if r.code != 0 || len(screens) != 1 {
		t.Fatalf("code %d, screens %v", r.code, screens)
	}
	got := screens[0]
	if got["text"] != "hello" || got["cursorRow"].(float64) != 1 || got["cursorCol"].(float64) != 0 ||
		got["cols"].(float64) != 80 || got["rows"].(float64) != 24 {
		t.Fatalf("screen = %v", got)
	}
}

func TestScreen_keepsWideCharactersWhole(t *testing.T) {
	s := start(t, []string{"sh", "-c", "printf '日本語 ok\\n'; sleep 1"}, nil)

	s.until("a screen frame", func() bool { return len(s.out.ofType("screen")) > 0 })
	s.wait()

	got := s.out.ofType("screen")[0]
	if got["text"] != "日本語 ok" || got["cursorRow"].(float64) != 1 {
		t.Fatalf("screen = %v", got)
	}
}

func TestExit_passesOnTheCodeOfTheProgram(t *testing.T) {
	s := start(t, []string{"sh", "-c", "exit 7"}, nil)

	r := s.wait()

	exit := s.out.ofType("exit")
	if r.code != 7 || len(exit) != 1 || exit[0]["code"].(float64) != 7 {
		t.Fatalf("code %d, exit frames %v", r.code, exit)
	}
	frames := s.out.frames()
	if frames[len(frames)-1]["t"] != "exit" {
		t.Fatalf("the exit frame is not last: %v", frames)
	}
}

func TestEndOfInput_hangsTheProgramUp(t *testing.T) {
	s := start(t, []string{"sleep", "30"}, nil)

	_ = s.in.Close()
	r := s.wait()

	if r.code != -1 {
		t.Fatalf("code = %d, want -1", r.code)
	}
}

func TestClose_killsAProgramThatIgnoresTheHangup(t *testing.T) {
	s := start(t, []string{"sh", "-c", "trap '' HUP; exec sleep 30"}, nil)
	s.until("the program to run", func() bool { return true })
	time.Sleep(300 * time.Millisecond)

	started := time.Now()
	s.send(`{"t":"close"}`)
	r := s.wait()

	if r.code != -1 || time.Since(started) > 10*time.Second {
		t.Fatalf("code %d after %v", r.code, time.Since(started))
	}
}

func TestOutput_isCutIntoFramesOfAtMostTheLimit(t *testing.T) {
	s := start(t, []string{"sh", "-c", "head -c 40000 /dev/zero | tr '\\0' a"},
		func(o *Options) { o.Gather = time.Minute })

	r := s.wait()

	if r.code != 0 || strings.Count(s.out.output(), "a") < 40000 {
		t.Fatalf("code %d, got %d bytes", r.code, strings.Count(s.out.output(), "a"))
	}
	for _, frame := range s.out.ofType("out") {
		data, _ := base64.StdEncoding.DecodeString(frame["d"].(string))
		if len(data) > 16*1024 {
			t.Fatalf("a frame of %d bytes", len(data))
		}
	}
}

func TestFramesThatMakeNoSense_areIgnored(t *testing.T) {
	s := start(t, []string{"cat"}, nil)

	s.send("not json")
	s.send(`{"t":"in","d":"!!not base64!!"}`)
	s.send(`{"t":"resize","cols":0,"rows":0}`)
	s.send(`{"t":"unknown"}`)
	s.typed("still alive\n")
	s.until("the echo", func() bool { return strings.Contains(s.out.output(), "still alive") })
	s.send(`{"t":"close"}`)
	s.wait()
}

func TestDrain_stopsReadingWhenSomethingElseHoldsTheTerminal(t *testing.T) {
	s := start(t, []string{"sh", "-c", "sleep 2 & exit 0"}, nil)

	started := time.Now()
	r := s.wait()

	if r.code != 0 || time.Since(started) > 1500*time.Millisecond {
		t.Fatalf("code %d after %v", r.code, time.Since(started))
	}
}

func TestTerminal_answersTheProgramsQuestions(t *testing.T) {
	s := start(t, []string{"sh", "-c", `stty raw -echo; printf '\033[6n'; head -c 6 | wc -c`}, nil)

	r := s.wait()

	if r.code != 0 || !strings.Contains(s.out.output(), "6") {
		t.Fatalf("code %d, output %q", r.code, s.out.output())
	}
}

func TestTerm_isXterm256color(t *testing.T) {
	s := start(t, []string{"sh", "-c", "echo $TERM"}, nil)

	s.wait()

	if !strings.Contains(s.out.output(), "xterm-256color") {
		t.Fatalf("output %q", s.out.output())
	}
}

func TestRun_failsWhenThereIsNothingToRunOrItCannotStart(t *testing.T) {
	if _, err := Run(strings.NewReader(""), io.Discard, New(nil, 80, 24)); err == nil {
		t.Fatal("an empty command ran")
	}
	if _, err := Run(strings.NewReader(""), io.Discard, New([]string{"/no/such/program"}, 80, 24)); err == nil ||
		!strings.Contains(err.Error(), "starting /no/such/program") {
		t.Fatalf("err = %v", err)
	}
	opts := New([]string{"cat"}, 80, 24)
	opts.open = func() (pty.Pty, error) { return nil, errors.New("no terminals") }
	if _, err := Run(strings.NewReader(""), io.Discard, opts); err == nil || !strings.Contains(err.Error(), "no terminals") {
		t.Fatalf("err = %v", err)
	}
}

func TestRun_usesTheDefaultTerminalWhenNoneIsGiven(t *testing.T) {
	opts := New([]string{"sh", "-c", "exit 0"}, 80, 24)
	opts.open = nil

	code, err := Run(strings.NewReader(""), io.Discard, opts)

	if err != nil {
		t.Fatalf("err = %v", err)
	}
	_ = code
}
