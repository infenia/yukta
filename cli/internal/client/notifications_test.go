// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package client

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

const inboxBody = `{"data":[{"id":"n1","key":"input:r1","kind":"ACTION","severity":"WARN","category":"input","title":"Deploy?","state":"ACTIVE","count":1,"seq":4,"createdAt":"t","updatedAt":"t","read":false,"dismissed":false,"executionId":"e1","inputRequestId":"r1"}]}`

func TestListNotifications_sendsFiltersAndDecodes(t *testing.T) {
	var query string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		query = r.URL.RawQuery
		_, _ = io.WriteString(w, inboxBody)
	}))
	defer server.Close()

	items, err := NewClient(server.URL).ListNotifications(NotificationQuery{
		State: "ACTIVE", Kind: "ACTION", Severity: "WARN", SessionID: "s1", Unread: true, Size: 200})
	if err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{"state=ACTIVE", "kind=ACTION", "severity=WARN", "sessionId=s1", "unread=true", "size=200"} {
		if !strings.Contains(query, want) {
			t.Errorf("query %q lacks %s", query, want)
		}
	}
	if len(items) != 1 || items[0].InputRequestID != "r1" || items[0].Kind != "ACTION" {
		t.Errorf("items = %+v", items)
	}
}

func TestListNotifications_withoutFiltersAndOnErrors(t *testing.T) {
	var query string
	var body = inboxBody
	status := 200
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		query = r.URL.RawQuery
		w.WriteHeader(status)
		_, _ = io.WriteString(w, body)
	}))
	defer server.Close()
	c := NewClient(server.URL)

	if _, err := c.ListNotifications(NotificationQuery{}); err != nil || query != "" {
		t.Errorf("err = %v, query = %q", err, query)
	}
	body = "not json"
	if _, err := c.ListNotifications(NotificationQuery{}); err == nil {
		t.Error("a body that is not JSON must fail")
	}
	status, body = 403, `{"message":"no"}`
	if _, err := c.ListNotifications(NotificationQuery{}); err == nil {
		t.Error("a refusal must fail")
	}
	if _, err := NewClient("http://127.0.0.1:1/\x7f").ListNotifications(NotificationQuery{}); err == nil {
		t.Error("a bad address must fail")
	}
}

func TestMarkNotificationsRead(t *testing.T) {
	var path, sent string
	var body = `{"data":{"marked":2}}`
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		b, _ := io.ReadAll(r.Body)
		path, sent = r.URL.Path, string(b)
		_, _ = io.WriteString(w, body)
	}))
	defer server.Close()
	c := NewClient(server.URL)

	if n, err := c.MarkNotificationsRead([]string{"a", "b"}, false); err != nil || n != 2 {
		t.Fatalf("n = %d, err = %v", n, err)
	}
	if path != "/api/notifications/read" || !strings.Contains(sent, `"ids":["a","b"]`) {
		t.Errorf("path = %s, body = %s", path, sent)
	}
	if _, err := c.MarkNotificationsRead(nil, true); err != nil || !strings.Contains(sent, `"all":true`) {
		t.Errorf("all: err = %v, body = %s", err, sent)
	}
	if _, err := c.MarkNotificationsRead(nil, false); err == nil {
		t.Error("nothing to mark must be refused")
	}
	body = "x"
	if _, err := c.MarkNotificationsRead(nil, true); err == nil {
		t.Error("a bad body must fail")
	}
	if _, err := NewClient("http://127.0.0.1:1/\x7f").MarkNotificationsRead(nil, true); err == nil {
		t.Error("a bad address must fail")
	}
	if _, err := NewClient("http://127.0.0.1:1").MarkNotificationsRead(nil, true); err == nil {
		t.Error("an unreachable server must fail")
	}
}

func TestDismissNotification(t *testing.T) {
	var path string
	status := 200
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		path = r.Method + " " + r.URL.Path
		w.WriteHeader(status)
		_, _ = io.WriteString(w, "{}")
	}))
	defer server.Close()
	c := NewClient(server.URL)

	if err := c.DismissNotification("n1"); err != nil || path != "POST /api/notifications/n1/dismiss" {
		t.Errorf("err = %v, path = %s", err, path)
	}
	if err := c.DismissNotification(""); err == nil {
		t.Error("an empty id must be refused")
	}
	status = 409
	if err := c.DismissNotification("n1"); err == nil {
		t.Error("a conflict must fail")
	}
	if err := NewClient("http://127.0.0.1:1/\x7f").DismissNotification("n1"); err == nil {
		t.Error("a bad address must fail")
	}
}

func TestGetNotificationPreferences_andWants(t *testing.T) {
	body := `{"data":{"levels":{"run.failed":"INBOX","input":"DESKTOP","other":"OFF"},"mutedSessions":["quiet"]}}`
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = io.WriteString(w, body)
	}))
	defer server.Close()
	c := NewClient(server.URL)

	p, err := c.GetNotificationPreferences()
	if err != nil {
		t.Fatal(err)
	}
	cases := []struct {
		n    Notification
		want bool
	}{
		{Notification{Category: "input"}, true},
		{Notification{Category: "run.failed"}, false},
		{Notification{Category: "other"}, false},
		{Notification{Category: "run.held"}, true},
		{Notification{Category: "input", SessionID: "quiet"}, false},
		{Notification{Category: "input", SessionID: "loud"}, true},
	}
	for _, tc := range cases {
		if got := p.Wants(tc.n); got != tc.want {
			t.Errorf("Wants(%+v) = %v, want %v", tc.n, got, tc.want)
		}
	}
	body = "x"
	if _, err := c.GetNotificationPreferences(); err == nil {
		t.Error("a bad body must fail")
	}
	if _, err := NewClient("http://127.0.0.1:1/\x7f").GetNotificationPreferences(); err == nil {
		t.Error("a bad address must fail")
	}
	if _, err := NewClient("http://127.0.0.1:1").GetNotificationPreferences(); err == nil {
		t.Error("an unreachable server must fail")
	}
}

func fast(c *Client) *Client {
	c.StreamWait = time.Millisecond
	c.StreamReconnects = 2
	return c
}

func TestStreamNotifications_reconnectsWithTheLastEventIDAndSkipsJunk(t *testing.T) {
	var connections atomic.Int32
	var resumedFrom atomic.Value
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		n := connections.Add(1)
		w.Header().Set("Content-Type", "text/event-stream")
		if n == 1 {
			fmt.Fprint(w, "id: e-1\nevent: created\ndata: {\"type\":\"created\",\"notification\":{\"id\":\"n1\",\"title\":\"first\"}}\n\n")
			fmt.Fprint(w, "data: not json\n\n")
			return // the connection drops
		}
		resumedFrom.Store(r.Header.Get("Last-Event-ID"))
		fmt.Fprint(w, "id: e-2\nevent: resolved\ndata: {\"type\":\"resolved\",\"notification\":{\"id\":\"n1\"}}\n\n")
	}))
	defer server.Close()

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	var types []string
	err := fast(NewClient(server.URL)).StreamNotifications(ctx, func(e NotificationEvent) error {
		types = append(types, e.Type)
		if e.Type == "resolved" {
			cancel()
		}
		return nil
	})

	if !errors.Is(err, context.Canceled) {
		t.Fatalf("err = %v", err)
	}
	if strings.Join(types, ",") != "created,resolved" {
		t.Errorf("types = %v", types)
	}
	if resumedFrom.Load() != "e-1" {
		t.Errorf("resumed from %v, want e-1", resumedFrom.Load())
	}
}

func TestStreamNotifications_endsOnAHandlerErrorAndAPermanentRefusal(t *testing.T) {
	frame := "id: e-1\ndata: {\"type\":\"created\"}\n\n"
	status := 200
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if status != 200 {
			w.WriteHeader(status)
			return
		}
		w.Header().Set("Content-Type", "text/event-stream")
		fmt.Fprint(w, frame)
	}))
	defer server.Close()
	c := fast(NewClient(server.URL))

	boom := errors.New("stop here")
	if err := c.StreamNotifications(context.Background(), func(NotificationEvent) error { return boom }); !errors.Is(err, boom) {
		t.Errorf("handler error: got %v", err)
	}
	status = 401
	if err := c.StreamNotifications(context.Background(), func(NotificationEvent) error { return nil }); !IsPermanent(err) {
		t.Errorf("a refused key must end the stream for good: %v", err)
	}
	if err := c.StreamNotifications(context.Background(), nil); err == nil {
		t.Error("a nil callback must be refused")
	}
}

func TestStreamNotifications_cancelledWhileWaitingToReconnect(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(503)
	}))
	defer server.Close()
	c := fast(NewClient(server.URL))
	c.StreamWait = time.Hour
	ctx, cancel := context.WithTimeout(context.Background(), 50*time.Millisecond)
	defer cancel()

	err := c.StreamNotifications(ctx, func(NotificationEvent) error { return nil })

	if !errors.Is(err, context.DeadlineExceeded) {
		t.Errorf("err = %v", err)
	}
}

func TestStreamNotifications_waitsAndTriesAgainAfterAFailure(t *testing.T) {
	var connections atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if connections.Add(1) == 1 {
			w.WriteHeader(503)
			return
		}
		w.Header().Set("Content-Type", "text/event-stream")
		fmt.Fprint(w, "id: e-1\ndata: {\"type\":\"created\"}\n\n")
	}))
	defer server.Close()
	boom := errors.New("seen")

	err := fast(NewClient(server.URL)).StreamNotifications(context.Background(),
		func(NotificationEvent) error { return boom })

	if !errors.Is(err, boom) || connections.Load() != 2 {
		t.Errorf("err = %v after %d connections, want the handler error after 2", err, connections.Load())
	}
}
