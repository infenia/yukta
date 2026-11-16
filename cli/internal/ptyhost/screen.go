// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package ptyhost

import (
	"io"
	"strings"

	"github.com/charmbracelet/x/vt"
)

// Screen is the virtual screen the host feeds with what the program draws. It is a small
// interface so that another emulator can replace charmbracelet/x/vt (the AC0 decision: vt10x is the
// fallback). Reading it yields the answers the terminal gives the program (a cursor position
// report, for example), which the host writes back to the program. There is no Close: the emulator
// races with its own Read when closed, and the host ends with the process.
type Screen interface {
	io.Reader
	io.Writer
	// Text is the screen as lines without trailing blanks.
	Text() string
	// Cursor is the cursor row and column, counted from 0.
	Cursor() (row, col int)
	// Resize changes the size.
	Resize(cols, rows int)
}

type vtScreen struct {
	*vt.Emulator
}

func newScreen(cols, rows int) Screen {
	return vtScreen{vt.NewEmulator(cols, rows)}
}

func (s vtScreen) Text() string {
	lines := strings.Split(s.String(), "\n")
	for i := range lines {
		lines[i] = strings.TrimRight(lines[i], " ")
	}
	return strings.TrimRight(strings.Join(lines, "\n"), "\n")
}

func (s vtScreen) Cursor() (int, int) {
	position := s.CursorPosition()
	return position.Y, position.X
}

func (s vtScreen) Resize(cols, rows int) { s.Emulator.Resize(cols, rows) }
