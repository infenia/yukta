// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package client

import (
	"context"
	"fmt"
	"net/url"
)

// StreamExecutionResults follows what reaches the terminal nodes of an execution.
// It reads the server's SSE endpoint and calls onEvent for each event with its JSON
// data (kind RESULT, NODE_END or EXECUTION_END). Kept events are replayed first; the
// stream closes after the EXECUTION_END event. A non-empty nodeID limits the
// stream to that terminal node.
func (c *Client) StreamExecutionResults(
	ctx context.Context,
	sessionID string,
	executionID string,
	nodeID string,
	onEvent func(event string, data string) error,
) error {
	if sessionID == "" || executionID == "" {
		return fmt.Errorf("sessionID and executionID cannot be empty")
	}
	if onEvent == nil {
		return fmt.Errorf("onEvent callback cannot be nil")
	}
	path := apiPath("/api/sessions/%s/executions/%s/results", sessionID, executionID)
	if nodeID != "" {
		path += "?nodeId=" + url.QueryEscape(nodeID)
	}

	resp, err := c.streamRequest(ctx, path)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	return ScanSSE(ctx, resp.Body, func(event SSEEvent) error {
		return onEvent(event.Event, event.Data)
	})
}
