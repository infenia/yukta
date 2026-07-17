// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package client

import (
	"context"
	"fmt"
)

// StreamExecutionLogs streams execution logs for a given session and execution.
// It reads from the server's SSE endpoint and calls onLine for each log entry as it arrives.
// The stream automatically closes when the execution reaches a terminal state on the server.
func (c *Client) StreamExecutionLogs(
	ctx context.Context,
	sessionID string,
	executionID string,
	onLine func(line string) error,
) error {
	if sessionID == "" {
		return fmt.Errorf("sessionID cannot be empty")
	}
	if executionID == "" {
		return fmt.Errorf("executionID cannot be empty")
	}
	if onLine == nil {
		return fmt.Errorf("onLine callback cannot be nil")
	}

	path := fmt.Sprintf("/api/sessions/%s/executions/%s/logs", sessionID, executionID)
	resp, err := c.streamRequest(ctx, path)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	// Scan SSE stream and call onLine for each log entry
	return ScanSSE(ctx, resp.Body, func(event SSEEvent) error {
		// Each SSE data: event is a pre-formatted log line from the server
		return onLine(event.Data)
	})
}
