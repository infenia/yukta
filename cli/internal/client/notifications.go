// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package client

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/url"
	"strconv"
	"time"
)

// Notification is one item of the caller's inbox (GET /api/notifications). The title and the body
// are written by a workflow, a rule or an AI agent: untrusted text.
type Notification struct {
	ID             string `json:"id"`
	Key            string `json:"key"`
	Kind           string `json:"kind"` // INFO, or ACTION for a question that waits for an answer
	Severity       string `json:"severity"`
	Category       string `json:"category"`
	Title          string `json:"title"`
	Body           string `json:"body,omitempty"`
	SessionID      string `json:"sessionId,omitempty"`
	WorkflowID     string `json:"workflowId,omitempty"`
	ExecutionID    string `json:"executionId,omitempty"`
	NodeID         string `json:"nodeId,omitempty"`
	InputRequestID string `json:"inputRequestId,omitempty"`
	State          string `json:"state"`
	Resolution     string `json:"resolution,omitempty"`
	Count          int    `json:"count"`
	Seq            int64  `json:"seq"`
	CreatedAt      string `json:"createdAt"`
	UpdatedAt      string `json:"updatedAt"`
	Read           bool   `json:"read"`
	Dismissed      bool   `json:"dismissed"`
}

// NotificationEvent is one event of the notification stream: created, updated, resolved, receipt
// or reset.
type NotificationEvent struct {
	Type           string        `json:"type"`
	Notification   *Notification `json:"notification,omitempty"`
	NotificationID string        `json:"notificationId,omitempty"`
	ReadAt         string        `json:"readAt,omitempty"`
	DismissedAt    string        `json:"dismissedAt,omitempty"`
}

// NotificationPreferences says what the caller wants to be told: a level per category (OFF, INBOX
// or DESKTOP) and the sessions muted.
type NotificationPreferences struct {
	Levels        map[string]string `json:"levels"`
	MutedSessions []string          `json:"mutedSessions"`
}

// Wants tells whether a notification may raise a desktop notification: its session is not muted and
// its category is not turned off or kept for the inbox only. A category with no entry notifies.
func (p NotificationPreferences) Wants(n Notification) bool {
	for _, muted := range p.MutedSessions {
		if n.SessionID != "" && muted == n.SessionID {
			return false
		}
	}
	level := p.Levels[n.Category]
	return level != "OFF" && level != "INBOX"
}

// NotificationQuery filters the inbox list; an empty field does not filter.
type NotificationQuery struct {
	State     string
	Kind      string
	Severity  string
	SessionID string
	Unread    bool
	Size      int
}

// ListNotifications reads the caller's inbox, newest change first.
func (c *Client) ListNotifications(q NotificationQuery) ([]Notification, error) {
	params := url.Values{}
	for name, value := range map[string]string{
		"state": q.State, "kind": q.Kind, "severity": q.Severity, "sessionId": q.SessionID,
	} {
		if value != "" {
			params.Set(name, value)
		}
	}
	if q.Unread {
		params.Set("unread", "true")
	}
	if q.Size > 0 {
		params.Set("size", strconv.Itoa(q.Size))
	}
	path := "/api/notifications"
	if len(params) > 0 {
		path += "?" + params.Encode()
	}
	req, err := c.newRequest("GET", path)
	if err != nil {
		return nil, err
	}
	data, err := c.doRequest(req)
	if err != nil {
		return nil, err
	}
	var response struct {
		Data []Notification `json:"data"`
	}
	if err := json.Unmarshal(data, &response); err != nil {
		return nil, fmt.Errorf("failed to unmarshal response: %w", err)
	}
	return response.Data, nil
}

// MarkNotificationsRead marks the listed notifications, or with all every one the caller sees, read.
// It returns how many were marked.
func (c *Client) MarkNotificationsRead(ids []string, all bool) (int, error) {
	if !all && len(ids) == 0 {
		return 0, errors.New("give notification ids, or all")
	}
	body, _ := json.Marshal(struct {
		IDs []string `json:"ids,omitempty"`
		All bool     `json:"all,omitempty"`
	}{ids, all}) // strings and a bool always encode
	req, err := c.newRequestWithBody("POST", "/api/notifications/read", body)
	if err != nil {
		return 0, err
	}
	data, err := c.doRequest(req)
	if err != nil {
		return 0, err
	}
	var response struct {
		Data struct {
			Marked int `json:"marked"`
		} `json:"data"`
	}
	if err := json.Unmarshal(data, &response); err != nil {
		return 0, fmt.Errorf("failed to unmarshal response: %w", err)
	}
	return response.Data.Marked, nil
}

// DismissNotification hides an info notification or a resolved question for the caller; a question
// that still waits cannot be dismissed (409).
func (c *Client) DismissNotification(id string) error {
	if id == "" {
		return errors.New("notification id cannot be empty")
	}
	req, err := c.newRequestWithBody("POST", apiPath("/api/notifications/%s/dismiss", id), []byte("{}"))
	if err != nil {
		return err
	}
	_, err = c.doRequest(req)
	return err
}

// GetNotificationPreferences reads what the caller wants to be told.
func (c *Client) GetNotificationPreferences() (NotificationPreferences, error) {
	req, err := c.newRequest("GET", "/api/notifications/preferences")
	if err != nil {
		return NotificationPreferences{}, err
	}
	data, err := c.doRequest(req)
	if err != nil {
		return NotificationPreferences{}, err
	}
	var response struct {
		Data NotificationPreferences `json:"data"`
	}
	if err := json.Unmarshal(data, &response); err != nil {
		return NotificationPreferences{}, fmt.Errorf("failed to unmarshal response: %w", err)
	}
	return response.Data, nil
}

// maxNotificationWait is the longest pause between two tries to reach the stream again.
const maxNotificationWait = time.Minute

// StreamNotifications follows the caller's notification stream until ctx ends, onEvent returns an
// error or the server refuses the key. A connection that ends, fails or goes quiet (a laptop that
// slept) is opened again after a growing pause, with the id of the last event, so the server
// replays what was missed; it sends reset when it cannot.
func (c *Client) StreamNotifications(ctx context.Context, onEvent func(NotificationEvent) error) error {
	if onEvent == nil {
		return errors.New("onEvent callback cannot be nil")
	}
	lastID := ""
	var fatal error
	failures := 0
	for {
		err := c.followSSEFrom(ctx, "/api/notifications/stream", func() bool { return false }, &lastID,
			func(sse SSEEvent) error {
				var event NotificationEvent
				if err := json.Unmarshal([]byte(sse.Data), &event); err != nil {
					return nil // a line that is not an event is skipped, the stream goes on
				}
				failures = 0
				if err := onEvent(event); err != nil {
					fatal = err
					return err
				}
				return nil
			})
		switch {
		case fatal != nil:
			return fatal
		case ctx.Err() != nil:
			return ctx.Err()
		case IsPermanent(err):
			return err
		}
		failures++
		pause := min(c.StreamWait*time.Duration(failures), maxNotificationWait)
		select {
		case <-time.After(pause):
			continue
		case <-ctx.Done():
			return ctx.Err()
		}
	}
}
