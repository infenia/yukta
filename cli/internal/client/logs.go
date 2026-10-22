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

	return c.streamLogs(ctx, executionID, apiPath("/api/sessions/%s/executions/%s/logs", sessionID, executionID), onLine)
}

// StreamExecutionLogEntries is like StreamExecutionLogs but emits each structured
// log entry as a JSON string.
func (c *Client) StreamExecutionLogEntries(
	ctx context.Context,
	sessionID string,
	executionID string,
	onLine func(line string) error,
) error {
	if sessionID == "" || executionID == "" {
		return fmt.Errorf("sessionID and executionID cannot be empty")
	}
	if onLine == nil {
		return fmt.Errorf("onLine callback cannot be nil")
	}
	return c.streamLogs(ctx, executionID, apiPath("/api/sessions/%s/executions/%s/logs/entries", sessionID, executionID), onLine)
}

func (c *Client) streamLogs(ctx context.Context, executionID, path string, onLine func(line string) error) error {
	// Each SSE data: event is a pre-formatted log line from the server
	return c.followSSE(ctx, path, c.executionDone(executionID), func(event SSEEvent) error {
		return onLine(event.Data)
	})
}
