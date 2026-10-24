// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package client

import (
	"bufio"
	"context"
	"fmt"
	"io"
	"mime"
	"net/http"
	"strings"
)

// SSEEvent represents one parsed Server-Sent Event.
type SSEEvent struct {
	Event string
	Data  string
	ID    string // the last id: seen on the stream so far; sent back as Last-Event-ID on a reconnect
}

// streamRequest opens a GET request expected to return text/event-stream.
// Unlike doRequest, it does NOT buffer the body; instead, it returns the live
// response for the caller to scan incrementally. The caller must close the response body.
func (c *Client) streamRequest(ctx context.Context, path string) (*http.Response, error) {
	return c.streamRequestFrom(ctx, path, "")
}

// streamRequestFrom is streamRequest that resumes after lastEventID (empty: from the start).
func (c *Client) streamRequestFrom(ctx context.Context, path, lastEventID string) (*http.Response, error) {
	req, err := c.newRequest("GET", path)
	if err != nil {
		return nil, err
	}

	req.Header.Set("Accept", "text/event-stream")
	if lastEventID != "" {
		req.Header.Set("Last-Event-ID", lastEventID)
	}
	req = req.WithContext(ctx)

	resp, err := c.HTTPClient.Do(req)
	if err != nil {
		return nil, fmt.Errorf("request failed: %w", err)
	}

	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		defer resp.Body.Close()
		body, _ := io.ReadAll(resp.Body)
		return nil, newAPIError(resp, body)
	}

	mediaType, _, err := mime.ParseMediaType(resp.Header.Get("Content-Type"))
	if err != nil || mediaType != "text/event-stream" {
		_ = resp.Body.Close()
		return nil, fmt.Errorf("unexpected content type %q, expected text/event-stream", resp.Header.Get("Content-Type"))
	}

	return resp, nil
}

// ScanSSE reads Server-Sent Events from r one at a time, calling onEvent for each
// fully-parsed event (blank-line-delimited data:/event: blocks). Multi-line data:
// fields are joined with newlines per the SSE spec. Returns when the stream ends
// (EOF, ctx cancellation, or onEvent returns an error).
func ScanSSE(ctx context.Context, r io.Reader, onEvent func(SSEEvent) error) error {
	return scanSSE(ctx, r, nil, onEvent)
}

// scanSSE is ScanSSE that also calls onLine (if not nil) for every line read, comments and server
// pings included, so a caller can tell a quiet connection from a dead one. An event still open at
// the end of the stream (no closing blank line) is dropped, as the SSE spec says: a connection cut
// mid-line would otherwise deliver half a document.
func scanSSE(ctx context.Context, r io.Reader, onLine func(), onEvent func(SSEEvent) error) error {
	if onEvent == nil {
		return fmt.Errorf("onEvent callback cannot be nil")
	}

	scanner := bufio.NewScanner(r)
	scanner.Buffer(make([]byte, 0, 64*1024), 1024*1024)

	var cur SSEEvent
	var data []string
	lastID := ""

	flush := func() error {
		if len(data) == 0 {
			return nil
		}
		cur.Data = strings.Join(data, "\n")
		cur.ID = lastID
		return onEvent(cur)
	}

	for scanner.Scan() {
		if err := ctx.Err(); err != nil {
			return err
		}
		if onLine != nil {
			onLine()
		}

		line := scanner.Text()

		switch {
		case line == "":
			// Blank line signals end of event
			if err := flush(); err != nil {
				return err
			}
			cur = SSEEvent{}
			data = nil

		case strings.HasPrefix(line, "data:"):
			// Strip "data:" and optional leading space per SSE spec
			dataValue := strings.TrimPrefix(line, "data:")
			dataValue = strings.TrimPrefix(dataValue, " ")
			data = append(data, dataValue)

		case strings.HasPrefix(line, "event:"):
			// Strip "event:" and trim whitespace
			cur.Event = strings.TrimSpace(strings.TrimPrefix(line, "event:"))

		case strings.HasPrefix(line, "id:"):
			lastID = strings.TrimSpace(strings.TrimPrefix(line, "id:"))

			// Other fields (retry:, comments starting with :) are intentionally ignored
		}
	}

	if err := scanner.Err(); err != nil {
		return err
	}
	return nil
}
