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
// Only Data is populated by Yukta streaming endpoints (no event:/id:/retry: fields).
type SSEEvent struct {
	Event string
	Data  string
}

// streamRequest opens a GET request expected to return text/event-stream.
// Unlike doRequest, it does NOT buffer the body; instead, it returns the live
// response for the caller to scan incrementally. The caller must close the response body.
func (c *Client) streamRequest(ctx context.Context, path string) (*http.Response, error) {
	req, err := c.newRequest("GET", path)
	if err != nil {
		return nil, err
	}

	req.Header.Set("Accept", "text/event-stream")
	req = req.WithContext(ctx)

	resp, err := c.httpDoer.Do(req)
	if err != nil {
		return nil, fmt.Errorf("request failed: %w", err)
	}

	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		defer resp.Body.Close()
		body, _ := io.ReadAll(resp.Body)
		return nil, fmt.Errorf("API error: status %d: %s", resp.StatusCode, string(body))
	}

	mediaType, _, err := mime.ParseMediaType(resp.Header.Get("Content-Type"))
	if err != nil || mediaType != "text/event-stream" {
		resp.Body.Close()
		return nil, fmt.Errorf("unexpected content type %q, expected text/event-stream", resp.Header.Get("Content-Type"))
	}

	return resp, nil
}

// ScanSSE reads Server-Sent Events from r one at a time, calling onEvent for each
// fully-parsed event (blank-line-delimited data:/event: blocks). Multi-line data:
// fields are joined with newlines per the SSE spec. Returns when the stream ends
// (EOF, ctx cancellation, or onEvent returns an error).
func ScanSSE(ctx context.Context, r io.Reader, onEvent func(SSEEvent) error) error {
	if onEvent == nil {
		return fmt.Errorf("onEvent callback cannot be nil")
	}

	scanner := bufio.NewScanner(r)
	scanner.Buffer(make([]byte, 0, 64*1024), 1024*1024)

	var cur SSEEvent
	var data []string

	for scanner.Scan() {
		if err := ctx.Err(); err != nil {
			return err
		}

		line := scanner.Text()

		switch {
		case line == "":
			// Blank line signals end of event
			if len(data) > 0 {
				cur.Data = strings.Join(data, "\n")
				if err := onEvent(cur); err != nil {
					return err
				}
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

			// Other fields (id:, retry:, comments starting with :) are intentionally ignored
		}
	}

	return scanner.Err()
}
