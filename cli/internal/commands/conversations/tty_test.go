// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

//go:build !windows

package conversations

import (
	"context"
	"io"
	"os"
	"strings"
	"syscall"
	"testing"
	"time"

	pty "github.com/aymanbagabas/go-pty"
)

// openPty returns the slave side of a new pseudo-terminal, the file a program sees as its terminal.
func openPty(t *testing.T) (*os.File, pty.Pty) {
	t.Helper()
	p, err := pty.New()
	if err != nil {
		t.Skipf("no pseudo-terminals here: %v", err)
	}
	t.Cleanup(func() { _ = p.Close() })
	return p.(pty.UnixPty).Slave(), p
}

// pipeEnds returns both ends of a pipe: neither is a terminal.
func pipeEnds(t *testing.T) (*os.File, *os.File) {
	t.Helper()
	r, w, err := os.Pipe()
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = r.Close(); _ = w.Close() })
	return r, w
}

func TestOpenTerminal_putsTheTerminalInRawModeAndRestoresIt(t *testing.T) {
	slave, _ := openPty(t)

	prompt, restore, err := openTerminal(slave, slave)
	if err != nil {
		t.Fatal(err)
	}
	if prompt == nil || restore == nil {
		t.Fatal("a terminal needs a prompt and a way back")
	}
	restore()
}

func TestOpenTerminal_failsOnAPipe(t *testing.T) {
	r, w := pipeEnds(t)

	if _, _, err := openTerminal(r, w); err == nil {
		t.Fatal("a pipe is not a terminal")
	}
}

func TestWindowSize_readsTheWindowOfTheTerminal(t *testing.T) {
	slave, p := openPty(t)
	if err := p.Resize(100, 30); err != nil {
		t.Fatal(err)
	}

	cols, rows, err := windowSize(slave)
	if err != nil {
		t.Fatal(err)
	}
	if cols != 100 || rows != 30 {
		t.Fatalf("size = %dx%d, want 100x30", cols, rows)
	}
}

func TestWindowSize_failsOnAPipe(t *testing.T) {
	_, w := pipeEnds(t)

	if _, _, err := windowSize(w); err == nil {
		t.Fatal("a pipe has no window size")
	}
}

func TestResizeSignals_firesWhenTheWindowChanges(t *testing.T) {
	fired, stop := resizeSignals()
	defer stop()

	if err := syscall.Kill(os.Getpid(), syscall.SIGWINCH); err != nil {
		t.Fatal(err)
	}
	select {
	case <-fired:
	case <-time.After(5 * time.Second):
		t.Fatal("no resize signal")
	}
}

func TestLineSource_isPlainWithoutInteractiveMode(t *testing.T) {
	a := &attacher{}

	src, done := a.lineSource(context.Background(), strings.NewReader("hi\n"), io.Discard)
	defer done()

	if src == nil || a.term != nil {
		t.Fatal("no terminal view without interactive mode")
	}
}

func TestUseTerminal_staysPlainWithoutRealFiles(t *testing.T) {
	a := &attacher{interactive: true}
	plain := newLines(strings.NewReader(""))

	src, done := a.useTerminal(context.Background(), strings.NewReader(""), io.Discard, plain)
	defer done()

	if src != plain || a.term != nil {
		t.Fatal("standard input that is not a file changes nothing")
	}
}

func TestUseTerminal_staysPlainWhenTheTerminalCannotBeOpened(t *testing.T) {
	r, w := pipeEnds(t)
	a := &attacher{interactive: true}
	plain := newLines(r)

	src, done := a.useTerminal(context.Background(), r, w, plain)
	defer done()

	if src != plain || a.term != nil {
		t.Fatal("a pipe is not a terminal, so the plain lines stay")
	}
}

func TestUseTerminal_drawsOverATerminalAndShowsTheRawViewOnRequest(t *testing.T) {
	slave, master := openPty(t)
	s := &terminalServer{stream: sse(1, "conversation", conv1)}
	a := &attacher{c: s.client(t), interactive: true, byID: map[string]*agentView{}}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	src, restore := a.lineSource(ctx, slave, slave)
	defer restore()
	if src == nil || a.out == nil || a.term == nil {
		t.Fatal("a real terminal gives a prompt and the raw view")
	}

	// the raw view reads the person's keys until Ctrl-] (the terminal is cooked, so the line ends too)
	ended := make(chan error, 1)
	go func() { ended <- a.term(ptyAgent) }()
	if _, err := master.Write([]byte("\x1d\n")); err != nil {
		t.Fatal(err)
	}
	select {
	case err := <-ended:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(10 * time.Second):
		t.Fatal("the raw view did not end on Ctrl-]")
	}
}
