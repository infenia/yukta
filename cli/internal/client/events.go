// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package client

import (
	"context"
	"encoding/json"
	"fmt"
	"net/url"
)

// Event kinds on the control bus (the "type" field of the envelope).
const (
	EventNodeStatusChanged     = "NodeStatusChanged"
	EventWorkflowStatusChanged = "WorkflowStatusChanged"
	EventControlOutcome        = "ControlOutcome"
	EventLogLine               = "LogLine"
)

// ExecutionEvent is one control-bus event. Which fields are set depends on Type:
// node and workflow status changes carry Status (and NodeID for nodes), control outcomes carry
// Command, Actor, Applied and Reason, log lines carry NodeID and Line.
type ExecutionEvent struct {
	Type        string `json:"type"`
	ExecutionID string `json:"executionId"`
	WorkflowID  string `json:"workflowId,omitempty"`
	NodeID      string `json:"nodeId,omitempty"`
	Status      string `json:"status,omitempty"`
	Command     string `json:"command,omitempty"`
	Actor       string `json:"actor,omitempty"`
	Applied     *bool  `json:"applied,omitempty"`
	Reason      string `json:"reason,omitempty"`
	Line        string `json:"line,omitempty"`
	// Severity is INFO, WARN or ERROR, set by the server on the envelope.
	Severity string `json:"severity,omitempty"`
	// Metadata is what a node reported; a failed node's reason is under "error".
	Metadata map[string]any `json:"metadata,omitempty"`
}

// IsTerminalStatus reports whether a workflow or node status is final. It mirrors the server's
// TaskStatus.isTerminal: everything except the three live states.
func IsTerminalStatus(status string) bool {
	switch status {
	case "", "PENDING", "QUEUED", "RUNNING":
		return false
	}
	return true
}

// StreamEvents streams control-bus events (GET /api/events/stream) until ctx ends or onEvent
// returns an error. executionID, eventType and severity (the lowest wanted: INFO, WARN or ERROR) are
// optional server-side filters.
// Events are not replayed by the server, so subscribe before the action you want to observe. A
// connection that ends or goes silent is an error.
func (c *Client) StreamEvents(
	ctx context.Context,
	executionID, eventType, severity string,
	onEvent func(ExecutionEvent) error,
) error {
	if onEvent == nil {
		return fmt.Errorf("onEvent callback cannot be nil")
	}

	params := url.Values{}
	if executionID != "" {
		params.Set("executionId", executionID)
	}
	if eventType != "" {
		params.Set("type", eventType)
	}
	if severity != "" {
		params.Set("severity", severity)
	}
	path := "/api/events/stream"
	if len(params) > 0 {
		path += "?" + params.Encode()
	}

	// The feed is not replayed, so a dropped connection cannot be resumed: it is reported, not hidden.
	return c.followSSE(ctx, path, nil, func(sse SSEEvent) error {
		var envelope struct {
			Type     string         `json:"type"`
			Severity string         `json:"severity"`
			Event    ExecutionEvent `json:"event"`
		}
		if err := json.Unmarshal([]byte(sse.Data), &envelope); err != nil {
			return fmt.Errorf("failed to unmarshal event: %w", err)
		}
		event := envelope.Event
		event.Type = envelope.Type
		event.Severity = envelope.Severity
		return onEvent(event)
	})
}
