// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package client

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"net/url"
	"strconv"
)

// Conversation is a conversation with an AI agent (GET /api/conversations). Reason is written by
// the agent: untrusted text.
type Conversation struct {
	ID              string   `json:"id"`
	ExecutionID     string   `json:"executionId"`
	RootExecutionID string   `json:"rootExecutionId"`
	SessionID       string   `json:"sessionId"`
	WorkflowID      string   `json:"workflowId"`
	NodeID          string   `json:"nodeId"`
	Harness         string   `json:"harness"`
	Protocol        string   `json:"protocol"`
	Model           string   `json:"model,omitempty"`
	Capabilities    []string `json:"capabilities"`
	State           string   `json:"state"`
	Outcome         string   `json:"outcome,omitempty"`
	Reason          string   `json:"reason,omitempty"`
	Turn            int      `json:"turn"`
	Queued          int      `json:"queued"`
	SteerAudience   []string `json:"steerAudience"`
	StartedAt       string   `json:"startedAt"`
	UpdatedAt       string   `json:"updatedAt"`
	EndedAt         string   `json:"endedAt,omitempty"`
	Seq             int64    `json:"seq"`
}

// Can reports whether the conversation has a capability (message, steer, interrupt, ask, resume,
// terminal).
func (c Conversation) Can(capability string) bool {
	for _, have := range c.Capabilities {
		if have == capability {
			return true
		}
	}
	return false
}

// ConversationEvent is one thing that happened in a conversation. What Data holds depends on Kind;
// it is written by the agent: untrusted text.
type ConversationEvent struct {
	Seq            int64          `json:"seq"`
	ConversationID string         `json:"conversationId"`
	Timestamp      string         `json:"timestamp"`
	Kind           string         `json:"kind"`
	ItemID         string         `json:"itemId,omitempty"`
	Data           map[string]any `json:"data"`
}

// ConversationEventsPage is a page of events and where the next one starts (0 on the last).
type ConversationEventsPage struct {
	Events []ConversationEvent
	Next   int64
}

// ConversationListOptions narrows ListConversations.
type ConversationListOptions struct {
	SessionID   string
	ExecutionID string // with it, the conversations of the runs below it too
	State       string // comma-separated: STARTING, RUNNING, WAITING, IDLE, ENDED
	Limit       int
}

func requireConversationID(id string) error {
	if id == "" {
		return errors.New("conversation id cannot be empty")
	}
	return nil
}

// ListConversations reads the conversations the key reaches, newest first.
func (c *Client) ListConversations(opts ConversationListOptions) ([]Conversation, error) {
	params := url.Values{}
	if opts.SessionID != "" {
		params.Set("sessionId", opts.SessionID)
	}
	if opts.ExecutionID != "" {
		params.Set("executionId", opts.ExecutionID)
	}
	if opts.State != "" {
		params.Set("state", opts.State)
	}
	if opts.Limit > 0 {
		params.Set("limit", strconv.Itoa(opts.Limit))
	}
	path := "/api/conversations"
	if len(params) > 0 {
		path += "?" + params.Encode()
	}
	return getAll[Conversation](c, path)
}

// GetConversation reads one conversation.
func (c *Client) GetConversation(id string) (*Conversation, error) {
	if err := requireConversationID(id); err != nil {
		return nil, err
	}
	var out Conversation
	if err := c.callData("GET", apiPath("/api/conversations/%s", id), nil, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

// ConversationEvents reads a page of the events after the sequence number after (0: from the start).
func (c *Client) ConversationEvents(id string, after int64, limit int) (*ConversationEventsPage, error) {
	if err := requireConversationID(id); err != nil {
		return nil, err
	}
	params := url.Values{"after": {strconv.FormatInt(after, 10)}}
	if limit > 0 {
		params.Set("limit", strconv.Itoa(limit))
	}
	req, err := c.newRequest("GET", apiPath("/api/conversations/%s/events", id)+"?"+params.Encode())
	if err != nil {
		return nil, err
	}
	body, header, err := c.doRequestHeader(req)
	if err != nil {
		return nil, err
	}
	var response struct {
		Data []ConversationEvent `json:"data"`
	}
	if err := json.Unmarshal(body, &response); err != nil {
		return nil, fmt.Errorf("failed to unmarshal response: %w", err)
	}
	page := &ConversationEventsPage{Events: response.Data}
	if next := header.Get(nextCursorHeader); next != "" {
		if page.Next, err = strconv.ParseInt(next, 10, 64); err != nil {
			return nil, fmt.Errorf("bad %s %q: %w", nextCursorHeader, next, err)
		}
	}
	return page, nil
}

// SendConversationMessage sends a message to the agent; it reports whether the message waits for
// the end of the agent's turn.
func (c *Client) SendConversationMessage(id, text string, interrupt bool) (queued bool, err error) {
	if err := requireConversationID(id); err != nil {
		return false, err
	}
	if text == "" {
		return false, errors.New("text cannot be empty")
	}
	body, _ := json.Marshal(struct {
		Text      string `json:"text"`
		Interrupt bool   `json:"interrupt,omitempty"`
	}{text, interrupt}) // a string and a bool always encode
	var out struct {
		Queued bool `json:"queued"`
	}
	err = c.callData("POST", apiPath("/api/conversations/%s/messages", id), body, &out)
	return out.Queued, err
}

// InterruptConversation stops the agent's current turn.
func (c *Client) InterruptConversation(id string) error {
	if err := requireConversationID(id); err != nil {
		return err
	}
	return c.callData("POST", apiPath("/api/conversations/%s/interrupt", id), nil, nil)
}

// EndConversation ends a conversation; outcome (success, failure or incomplete) may be empty.
func (c *Client) EndConversation(id, outcome, reason string) error {
	if err := requireConversationID(id); err != nil {
		return err
	}
	body, _ := json.Marshal(struct {
		Outcome string `json:"outcome,omitempty"`
		Reason  string `json:"reason,omitempty"`
	}{outcome, reason}) // two strings always encode
	return c.callData("POST", apiPath("/api/conversations/%s/end", id), body, nil)
}

// TerminalInput types keys into the terminal of an agent (up to 8 KB).
func (c *Client) TerminalInput(id string, keys []byte) error {
	if err := requireConversationID(id); err != nil {
		return err
	}
	body, _ := json.Marshal(struct {
		Data string `json:"data"`
	}{base64.StdEncoding.EncodeToString(keys)}) // a string always encodes
	return c.callData("POST", apiPath("/api/conversations/%s/terminal/input", id), body, nil)
}

// ResizeTerminal sets the size of the terminal of an agent.
func (c *Client) ResizeTerminal(id string, cols, rows int) error {
	if err := requireConversationID(id); err != nil {
		return err
	}
	body, _ := json.Marshal(struct {
		Cols int `json:"cols"`
		Rows int `json:"rows"`
	}{cols, rows}) // two numbers always encode
	return c.callData("POST", apiPath("/api/conversations/%s/terminal/resize", id), body, nil)
}

// ConversationFollow says what StreamConversations follows and resumes.
type ConversationFollow struct {
	// ExecutionID is the root run; the stream ends when it does.
	ExecutionID string
	// ConversationID narrows the stream to one conversation (empty: all of the run tree).
	ConversationID string
	// Terminal asks for the terminal output too; false sends screens only.
	Terminal bool
	// LastEventID resumes after a sequence number, and is updated as events arrive.
	LastEventID string
}

// StreamConversations follows the conversations of a run and of the runs below it. onConversation
// gets every change of a conversation, onEvent every event. A dropped connection is reopened with
// Last-Event-ID until the run has ended; the stream returns nil then.
func (c *Client) StreamConversations(
	ctx context.Context,
	follow *ConversationFollow,
	onConversation func(Conversation) error,
	onEvent func(ConversationEvent) error,
) error {
	if follow == nil || follow.ExecutionID == "" {
		return errors.New("executionID cannot be empty")
	}
	params := url.Values{"tree": {"true"}, "terminal": {strconv.FormatBool(follow.Terminal)}}
	if follow.ConversationID != "" {
		params.Set("conversationId", follow.ConversationID)
	}
	path := apiPath("/api/executions/%s/conversations/stream", follow.ExecutionID) + "?" + params.Encode()
	return c.followSSEFrom(ctx, path, c.executionDone(follow.ExecutionID), &follow.LastEventID, func(sse SSEEvent) error {
		switch sse.Event {
		case "conversation":
			var conversation Conversation
			if err := json.Unmarshal([]byte(sse.Data), &conversation); err != nil {
				return fmt.Errorf("failed to unmarshal conversation: %w", err)
			}
			return onConversation(conversation)
		case "event":
			var event ConversationEvent
			if err := json.Unmarshal([]byte(sse.Data), &event); err != nil {
				return fmt.Errorf("failed to unmarshal event: %w", err)
			}
			return onEvent(event)
		default:
			return nil
		}
	})
}

// callData sends a request and decodes the "data" of the answer into out (nil: ignore it).
func (c *Client) callData(method, path string, body []byte, out any) error {
	req, err := c.newRequestWithBody(method, path, body)
	if err != nil {
		return err
	}
	data, err := c.doRequest(req)
	if err != nil {
		return err
	}
	if out == nil || len(data) == 0 {
		return nil
	}
	var response struct {
		Data json.RawMessage `json:"data"`
	}
	if err := json.Unmarshal(data, &response); err != nil {
		return fmt.Errorf("failed to unmarshal response: %w", err)
	}
	if len(response.Data) == 0 || string(response.Data) == "null" {
		return nil
	}
	if err := json.Unmarshal(response.Data, out); err != nil {
		return fmt.Errorf("failed to unmarshal response: %w", err)
	}
	return nil
}
