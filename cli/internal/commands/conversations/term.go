// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package conversations

import (
	"context"
	"encoding/base64"
	"fmt"
	"io"
	"strings"

	"com.infenia.yukta/go-cli/internal/client"
)

// leaveKey is Ctrl-]: it ends the raw terminal view and goes back to the prompt.
const leaveKey = 0x1d

// terminalIO is the person's terminal as the raw view needs it.
type terminalIO struct {
	in  io.Reader
	out io.Writer
	// size is the window size in columns and rows.
	size func() (cols, rows int, err error)
	// resized fires when the window changes size; nil when the system has no such signal.
	resized <-chan struct{}
}

// passThrough shows the terminal of a pty agent as the person's own: what the program writes goes to
// out as it comes (a screen event redraws the whole view), what the person types goes to the program
// in as few requests as the reads make, the window size follows, and Ctrl-] leaves. The terminal must
// be in raw mode already. Nothing here is reviewed: it is the same as typing in the web UI.
func passThrough(ctx context.Context, c *client.Client, conversation client.Conversation, t terminalIO) error {
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	problems := make(chan error, 2)
	follow := &client.ConversationFollow{
		ExecutionID:    conversation.RootExecutionID,
		ConversationID: conversation.ID,
		Terminal:       true,
	}
	go func() {
		err := c.StreamConversations(ctx, follow,
			func(client.Conversation) error { return nil },
			func(e client.ConversationEvent) error { return draw(t.out, e) })
		if err != nil && ctx.Err() == nil {
			problems <- fmt.Errorf("the terminal stream ended: %w", err)
		}
	}()
	resize := func() {
		if cols, rows, err := t.size(); err == nil {
			if err := c.ResizeTerminal(conversation.ID, cols, rows); err != nil {
				problems <- err
			}
		}
	}
	resize()
	go func() {
		for {
			select {
			case <-t.resized:
				resize()
			case <-ctx.Done():
				return
			}
		}
	}()

	buf := make([]byte, 256)
	for {
		select {
		case err := <-problems:
			return err
		default:
		}
		n, err := t.in.Read(buf)
		keys := buf[:n]
		leave := false
		if at := indexByte(keys, leaveKey); at >= 0 {
			keys, leave = keys[:at], true
		}
		if len(keys) > 0 {
			if sendErr := c.TerminalInput(conversation.ID, keys); sendErr != nil {
				return sendErr
			}
		}
		if leave || err != nil {
			return nil
		}
	}
}

func indexByte(b []byte, c byte) int {
	for i, v := range b {
		if v == c {
			return i
		}
	}
	return -1
}

// draw writes what a terminal event holds: output as bytes, a screen as a fresh start.
func draw(out io.Writer, e client.ConversationEvent) error {
	if e.Kind != "TERMINAL" {
		return nil
	}
	if screen, ok := e.Data["screen"].(string); ok {
		_, err := io.WriteString(out, "\x1b[2J\x1b[H"+strings.ReplaceAll(screen, "\n", "\r\n"))
		return err
	}
	if data, ok := e.Data["data"].(string); ok {
		raw, err := base64.StdEncoding.DecodeString(data)
		if err != nil {
			return nil // a chunk that is not base64 is dropped, the stream goes on
		}
		_, err = out.Write(raw)
		return err
	}
	return nil
}
