// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package ptyhost

import (
	"encoding/json"
	"io"
	"sync"
)

// Frame is a frame of the server: "in" (D, base64 bytes for the program), "resize" (Cols, Rows)
// or "close".
type Frame struct {
	T    string `json:"t"`
	D    string `json:"d"`
	Cols int    `json:"cols"`
	Rows int    `json:"rows"`
}

// outFrame carries bytes the program wrote.
type outFrame struct {
	T string `json:"t"`
	D string `json:"d"`
}

// screenFrame carries the settled screen.
type screenFrame struct {
	T         string `json:"t"`
	Cols      int    `json:"cols"`
	Rows      int    `json:"rows"`
	Text      string `json:"text"`
	Styled    string `json:"styled,omitempty"`
	CursorRow int    `json:"cursorRow"`
	CursorCol int    `json:"cursorCol"`
}

// exitFrame ends the conversation with the code of the program.
type exitFrame struct {
	T    string `json:"t"`
	Code int    `json:"code"`
}

// encoder writes frames, one per line; it is safe for concurrent use because output, the screen
// timer and the exit frame come from different goroutines.
type encoder struct {
	mu  sync.Mutex
	out io.Writer
}

func (e *encoder) write(frame any) {
	line, _ := json.Marshal(frame) // these frames always marshal
	e.mu.Lock()
	defer e.mu.Unlock()
	_, _ = e.out.Write(append(line, '\n'))
}
