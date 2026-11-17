// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

// Package ptyhost runs a program in a pseudo-terminal and speaks frames about it on standard input
// and output, so that the Yukta server needs no native terminal code. The frames are JSON objects,
// one per line: the server sends "in", "resize" and "close"; the host sends "out", "screen" and
// "exit" (see Frame).
package ptyhost

import (
	"bufio"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"sync"
	"syscall"
	"time"

	"github.com/aymanbagabas/go-pty"
)

// Options say what to run and how the output is framed. Use New for the defaults of the plan.
type Options struct {
	// Command is the program and its arguments.
	Command []string
	// Cols and Rows are the size of the terminal at the start.
	Cols, Rows int
	// Gather is how long output is collected into one frame.
	Gather time.Duration
	// GatherMax is the most bytes of one frame.
	GatherMax int
	// Quiet is how long the output must stay silent before a changed screen is sent.
	Quiet time.Duration
	// Grace is how long a program has to end after SIGHUP before it is killed.
	Grace time.Duration
	// Drain is how long output is read after the program ended.
	Drain time.Duration

	open func() (pty.Pty, error)
}

// New returns the options of the plan: frames of at most 16 KB or 30 ms, a screen after 400 ms of
// quiet, SIGKILL 5 s after SIGHUP.
func New(command []string, cols, rows int) Options {
	return Options{
		Command:   command,
		Cols:      cols,
		Rows:      rows,
		Gather:    30 * time.Millisecond,
		GatherMax: 16 * 1024,
		Quiet:     400 * time.Millisecond,
		Grace:     5 * time.Second,
		Drain:     time.Second,
		open:      pty.New,
	}
}

// host is one program in one terminal.
type host struct {
	opts   Options
	term   pty.Pty
	cmd    *pty.Cmd
	screen Screen
	enc    *encoder

	mu       sync.Mutex // guards what follows
	pending  []byte
	gathered *time.Timer
	quiet    *time.Timer
	last     screenFrame // the last screen frame sent
	hangup   sync.Once
}

// Run starts the program, reads frames from in, writes frames to out and returns the exit code of
// the program once the "exit" frame is written. It fails when the program cannot be started.
func Run(in io.Reader, out io.Writer, opts Options) (int, error) {
	if len(opts.Command) == 0 {
		return 0, errors.New("no command to run")
	}
	open := opts.open
	if open == nil {
		open = pty.New
	}
	term, err := open()
	if err != nil {
		return 0, fmt.Errorf("opening the terminal: %w", err)
	}
	defer func() { _ = term.Close() }()
	_ = term.Resize(opts.Cols, opts.Rows)
	cmd := term.Command(opts.Command[0], opts.Command[1:]...)
	cmd.Env = append(os.Environ(), "TERM=xterm-256color")
	if err := cmd.Start(); err != nil {
		return 0, fmt.Errorf("starting %s: %w", opts.Command[0], err)
	}
	h := &host{
		opts:   opts,
		term:   term,
		cmd:    cmd,
		screen: newScreen(opts.Cols, opts.Rows),
		enc:    &encoder{out: out},
	}
	return h.run(in), nil
}

func (h *host) run(in io.Reader) int {
	drained := make(chan struct{})
	go h.pump(drained)
	// what the terminal answers the program; it ends with the process (the emulator cannot be
	// closed without a race in its own Read)
	go func() { _, _ = io.Copy(h.term, h.screen) }()
	go h.control(in)
	_ = h.cmd.Wait()
	select {
	case <-drained:
	case <-time.After(h.opts.Drain):
		// something else still holds the terminal open: stop reading
		_ = h.term.Close()
	}
	h.stopTimers()
	h.settle()
	code := h.cmd.ProcessState.ExitCode()
	h.enc.write(exitFrame{T: "exit", Code: code})
	return code
}

// pump reads what the program writes until the terminal ends.
func (h *host) pump(drained chan<- struct{}) {
	defer close(drained)
	buf := make([]byte, 4096)
	for {
		n, err := h.term.Read(buf)
		if n > 0 {
			h.output(buf[:n])
		}
		if err != nil {
			return
		}
	}
}

// output feeds the screen and gathers the bytes into frames.
func (h *host) output(data []byte) {
	h.mu.Lock()
	defer h.mu.Unlock()
	_, _ = h.screen.Write(data)
	h.pending = append(h.pending, data...)
	if len(h.pending) >= h.opts.GatherMax {
		h.flushLocked()
	} else if h.gathered == nil {
		h.gathered = time.AfterFunc(h.opts.Gather, h.flush)
	}
	if h.quiet != nil {
		h.quiet.Stop()
	}
	h.quiet = time.AfterFunc(h.opts.Quiet, h.screenFrame)
}

func (h *host) flush() {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.flushLocked()
}

func (h *host) flushLocked() {
	if h.gathered != nil {
		h.gathered.Stop()
		h.gathered = nil
	}
	for len(h.pending) > 0 {
		n := min(len(h.pending), h.opts.GatherMax)
		h.enc.write(outFrame{T: "out", D: base64.StdEncoding.EncodeToString(h.pending[:n])})
		h.pending = h.pending[n:]
	}
	h.pending = nil
}

// screenFrame sends the screen when it differs from the one sent last.
func (h *host) screenFrame() {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.flushLocked()
	h.screenLocked()
}

func (h *host) screenLocked() {
	row, col := h.screen.Cursor()
	frame := screenFrame{
		T:         "screen",
		Cols:      h.opts.Cols,
		Rows:      h.opts.Rows,
		Text:      h.screen.Text(),
		Styled:    h.screen.Styled(),
		CursorRow: row,
		CursorCol: col,
	}
	if frame == h.last {
		return
	}
	h.last = frame
	h.enc.write(frame)
}

func (h *host) stopTimers() {
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.quiet != nil {
		h.quiet.Stop()
	}
}

// settle writes what is still gathered and the final screen.
func (h *host) settle() {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.flushLocked()
	h.screenLocked()
}

// control reads the frames of the server. The end of input closes the program like a "close" frame.
func (h *host) control(in io.Reader) {
	reader := bufio.NewReader(in)
	for {
		line, err := reader.ReadBytes('\n')
		h.apply(line)
		if err != nil {
			h.close()
			return
		}
	}
}

func (h *host) apply(line []byte) {
	var frame Frame
	if json.Unmarshal(line, &frame) != nil {
		return
	}
	switch frame.T {
	case "in":
		if data, err := base64.StdEncoding.DecodeString(frame.D); err == nil {
			_, _ = h.term.Write(data)
		}
	case "resize":
		if frame.Cols > 0 && frame.Rows > 0 {
			h.resize(frame.Cols, frame.Rows)
		}
	case "close":
		h.close()
	}
}

func (h *host) resize(cols, rows int) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.opts.Cols, h.opts.Rows = cols, rows
	_ = h.term.Resize(cols, rows)
	h.screen.Resize(cols, rows)
}

// close hangs the program up, and kills it when it is still there after the grace time.
func (h *host) close() {
	h.hangup.Do(func() {
		_ = h.cmd.Process.Signal(syscall.SIGHUP)
		time.AfterFunc(h.opts.Grace, func() { _ = h.cmd.Process.Kill() })
	})
}
