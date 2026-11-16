// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package conversations

import (
	"context"
	"io"
	"os"

	"com.infenia.yukta/go-cli/internal/client"
	"golang.org/x/term"
)

// The glue to a real terminal; it cannot run without one, so the Makefile waives its coverage.

// stdio joins a terminal's input and output into the one value term.NewTerminal reads and writes.
type stdio struct {
	io.Reader
	io.Writer
}

// openTerminal puts the terminal in raw mode and returns a prompt that draws agent output above the
// line being typed, and the function that sets the terminal back.
func openTerminal(in, out *os.File) (*term.Terminal, func(), error) {
	state, err := term.MakeRaw(int(in.Fd()))
	if err != nil {
		return nil, nil, err
	}
	restore := func() { _ = term.Restore(int(in.Fd()), state) }
	return term.NewTerminal(stdio{in, out}, "> "), restore, nil
}

// windowSize is the size of the person's window.
func windowSize(out *os.File) (int, int, error) {
	return term.GetSize(int(out.Fd()))
}

// lineSource is where the lines of the person come from: the terminal prompt when attach is
// interactive and there is a real terminal, else the plain input.
func (a *attacher) lineSource(ctx context.Context, in io.Reader, out io.Writer) (lineSource, func()) {
	plain := newLines(in)
	if !a.interactive {
		return plain, func() {}
	}
	return a.useTerminal(ctx, in, out, plain)
}

// useTerminal switches attach to the person's own terminal: a prompt with line editing that agent
// output is drawn above, and /term for the raw view. Without a real terminal it changes nothing.
func (a *attacher) useTerminal(ctx context.Context, in io.Reader, out io.Writer, plain lineSource) (lineSource, func()) {
	inFile, inOK := in.(*os.File)
	outFile, outOK := out.(*os.File)
	if !inOK || !outOK {
		return plain, func() {}
	}
	prompt, restore, err := openTerminal(inFile, outFile)
	if err != nil {
		return plain, func() {}
	}
	a.out = prompt
	a.term = func(c client.Conversation) error {
		resized, stop := resizeSignals()
		defer stop()
		return passThrough(ctx, a.c, c, terminalIO{
			in:      inFile,
			out:     outFile,
			size:    func() (int, int, error) { return windowSize(outFile) },
			resized: resized,
		})
	}
	return prompt, restore
}
