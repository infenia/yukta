// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package client

import (
	"context"
	"errors"
	"fmt"
	"time"
)

// Defaults for a followed stream; the server pings every 15 s, so 45 s is three missed pings.
const (
	DefaultStreamIdle       = 45 * time.Second
	DefaultStreamReconnects = 5
	DefaultStreamWait       = time.Second
)

// callbackError marks an error returned by the caller's handler, which ends the stream for good.
type callbackError struct{ err error }

func (e callbackError) Error() string { return e.err.Error() }
func (e callbackError) Unwrap() error { return e.err }

// followSSE reads an event stream until it is complete. A connection that ends, fails or goes quiet
// for longer than c.StreamIdle is reopened with Last-Event-ID (at most c.StreamReconnects times in
// a row, with a growing pause) as long as finished() says the thing being watched has not ended.
// A stream that ends once finished() is true returns nil; one that cannot be completed returns an
// error, so a dropped connection never looks like a finished run. A nil finished means the stream
// cannot be resumed: any end of it is an error.
func (c *Client) followSSE(
	ctx context.Context,
	path string,
	finished func() bool,
	onEvent func(SSEEvent) error,
) error {
	lastID := ""
	failures := 0
	connected := false
	for {
		events, err := c.streamOnce(ctx, path, lastID, func(e SSEEvent) error {
			if e.ID != "" {
				lastID = e.ID
			}
			return onEvent(e)
		}, &connected)
		var cb callbackError
		switch {
		case errors.As(err, &cb):
			return cb.err
		case ctx.Err() != nil:
			return ctx.Err()
		case !connected:
			return err
		case IsPermanent(err):
			return err
		case finished != nil && finished():
			return nil
		case finished == nil:
			return fmt.Errorf("event stream ended unexpectedly: %w", orEOF(err))
		}
		if events > 0 {
			failures = 0
		}
		failures++
		if failures > c.StreamReconnects {
			return fmt.Errorf("stream ended before the execution finished (gave up after %d reconnects): %w", c.StreamReconnects, orEOF(err))
		}
		pause := c.StreamWait * time.Duration(failures)
		var ae *apiError
		if errors.As(err, &ae) && ae.retryAfter > pause {
			pause = min(ae.retryAfter, maxRetryWait)
		}
		select {
		case <-time.After(pause):
		case <-ctx.Done():
			return ctx.Err()
		}
	}
}

// IsPermanent tells an API answer that asking again will not change (401, 403, 404, ...).
func IsPermanent(err error) bool {
	var ae *apiError
	return errors.As(err, &ae) && ae.status >= 400 && ae.status < 500 && !retryableStatus(ae.status)
}

func orEOF(err error) error {
	if err == nil {
		return errors.New("connection closed")
	}
	return err
}

// streamOnce makes one connection and returns how many events it delivered and why it ended.
func (c *Client) streamOnce(
	ctx context.Context,
	path, lastID string,
	onEvent func(SSEEvent) error,
	connected *bool,
) (int, error) {
	connCtx, cancel := context.WithCancel(ctx)
	defer cancel()

	// Armed before the request so a server that never sends headers cannot hang it.
	var idle *time.Timer
	var onLine func()
	if c.StreamIdle > 0 {
		idle = time.AfterFunc(c.StreamIdle, cancel)
		defer idle.Stop()
		onLine = func() { idle.Reset(c.StreamIdle) }
	}

	resp, err := c.streamRequestFrom(connCtx, path, lastID)
	if err != nil {
		if ctx.Err() == nil && connCtx.Err() != nil {
			err = errors.New("no response from the server")
		}
		return 0, err
	}
	defer resp.Body.Close()
	*connected = true
	if idle != nil {
		idle.Reset(c.StreamIdle)
	}

	events := 0
	err = scanSSE(connCtx, resp.Body, onLine, func(e SSEEvent) error {
		events++
		if err := onEvent(e); err != nil {
			return callbackError{err}
		}
		return nil
	})
	if ctx.Err() == nil && connCtx.Err() != nil {
		err = errors.New("no data or ping from the server")
	}
	return events, err
}

// executionDone reports whether an execution has reached a final status (false if it cannot tell).
func (c *Client) executionDone(executionID string) func() bool {
	return func() bool {
		p, err := c.GetExecution(executionID)
		return err == nil && IsTerminalStatus(p.Status)
	}
}
