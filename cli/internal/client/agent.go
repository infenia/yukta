// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package client

import (
	"context"
	"fmt"
	"strconv"
)

// AgentStream follows the signals of the supervisor whose credential this client carries,
// calling onSignal with each event's data (a JSON object with cursor, kind and signal) and its
// cursor. It resumes after the given cursor (0 for the signals still remembered, -1 for new ones
// only) and returns when the stream ends or onSignal fails.
func (c *Client) AgentStream(ctx context.Context, after int64, onSignal func(data string, cursor string) error) error {
	resp, err := c.streamRequest(ctx, "/api/agent/v1/events?after="+strconv.FormatInt(after, 10))
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	return ScanSSE(ctx, resp.Body, func(event SSEEvent) error {
		if event.Data == "" {
			return nil
		}
		return onSignal(event.Data, event.ID)
	})
}

// AgentSend posts a JSON body to a path of the agent API.
func (c *Client) AgentSend(path string, body []byte) error {
	if len(path) < len("/api/agent/v1/") || path[:len("/api/agent/v1/")] != "/api/agent/v1/" {
		return fmt.Errorf("not an agent API path: %s", path)
	}
	_, err := c.dataCall("POST", path, body)
	return err
}
