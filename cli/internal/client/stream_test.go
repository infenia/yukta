// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package client

import (
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestStreamRequest_success(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != "GET" {
			t.Errorf("expected GET, got %s", r.Method)
		}
		if r.URL.Path != "/api/test/stream" {
			t.Errorf("expected /api/test/stream, got %s", r.URL.Path)
		}

		w.Header().Set("Content-Type", "text/event-stream")
		flusher := w.(http.Flusher)

		fmt.Fprint(w, "data: event 1\n\n")
		flusher.Flush()

		fmt.Fprint(w, "data: event 2\n\n")
		flusher.Flush()
	}))
	defer server.Close()

	c := NewClient(server.URL)
	resp, err := c.streamRequest(context.Background(), "/api/test/stream")

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		t.Errorf("expected status 200, got %d", resp.StatusCode)
	}
}

func TestStreamRequest_invalidPath(t *testing.T) {
	c := NewClient("http://example.com")
	_, err := c.streamRequest(context.Background(), "missing-leading-slash")

	if err == nil {
		t.Fatal("expected error for invalid path, got nil")
	}
	if !strings.Contains(err.Error(), "path must start with /") {
		t.Errorf("expected path validation error, got: %v", err)
	}
}

func TestStreamRequest_apiError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNotFound)
		_, _ = w.Write([]byte("Stream not found"))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	_, err := c.streamRequest(context.Background(), "/api/test/stream")

	if err == nil {
		t.Fatal("expected error, got nil")
	}
	if !strings.Contains(err.Error(), "API error") || !strings.Contains(err.Error(), "404") {
		t.Errorf("expected 404 API error, got: %v", err)
	}
}

func TestStreamRequest_wrongContentType(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"error":"not a stream"}`))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	_, err := c.streamRequest(context.Background(), "/api/test/stream")

	if err == nil {
		t.Fatal("expected error for wrong content type, got nil")
	}
	if !strings.Contains(err.Error(), "text/event-stream") {
		t.Errorf("expected content-type error, got: %v", err)
	}
}

func TestStreamRequest_missingContentType(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))
	defer server.Close()

	c := NewClient(server.URL)
	_, err := c.streamRequest(context.Background(), "/api/test/stream")

	if err == nil {
		t.Fatal("expected error for missing content type, got nil")
	}
	if !strings.Contains(err.Error(), "text/event-stream") {
		t.Errorf("expected content-type error, got: %v", err)
	}
}

func TestScanSSE_nilOnEvent(t *testing.T) {
	err := ScanSSE(context.Background(), strings.NewReader("data: hello\n\n"), nil)

	if err == nil {
		t.Fatal("expected error for nil onEvent, got nil")
	}
	if !strings.Contains(err.Error(), "nil") {
		t.Errorf("expected nil callback error message, got: %v", err)
	}
}

func TestScanSSE_singleEvent(t *testing.T) {
	input := "data: hello world\n\n"

	var events []SSEEvent
	err := ScanSSE(context.Background(), strings.NewReader(input), func(e SSEEvent) error {
		events = append(events, e)
		return nil
	})

	if err != nil {
		t.Errorf("unexpected error: %v", err)
	}
	if len(events) != 1 {
		t.Errorf("expected 1 event, got %d", len(events))
	}
	if events[0].Data != "hello world" {
		t.Errorf("expected data 'hello world', got %q", events[0].Data)
	}
}

func TestScanSSE_multipleEvents(t *testing.T) {
	input := "data: event 1\n\ndata: event 2\n\ndata: event 3\n\n"

	var events []SSEEvent
	err := ScanSSE(context.Background(), strings.NewReader(input), func(e SSEEvent) error {
		events = append(events, e)
		return nil
	})

	if err != nil {
		t.Errorf("unexpected error: %v", err)
	}
	if len(events) != 3 {
		t.Errorf("expected 3 events, got %d", len(events))
	}
	if events[0].Data != "event 1" || events[1].Data != "event 2" || events[2].Data != "event 3" {
		t.Errorf("events mismatch: %v", events)
	}
}

func TestScanSSE_multilineData(t *testing.T) {
	input := "data: line 1\ndata: line 2\ndata: line 3\n\n"

	var events []SSEEvent
	err := ScanSSE(context.Background(), strings.NewReader(input), func(e SSEEvent) error {
		events = append(events, e)
		return nil
	})

	if err != nil {
		t.Errorf("unexpected error: %v", err)
	}
	if len(events) != 1 {
		t.Errorf("expected 1 event, got %d", len(events))
	}
	expected := "line 1\nline 2\nline 3"
	if events[0].Data != expected {
		t.Errorf("expected %q, got %q", expected, events[0].Data)
	}
}

func TestScanSSE_contextCancellation(t *testing.T) {
	input := "data: event 1\n\ndata: event 2\n\n"

	ctx, cancel := context.WithCancel(context.Background())
	eventCount := 0

	err := ScanSSE(ctx, strings.NewReader(input), func(e SSEEvent) error {
		eventCount++
		cancel() // Cancel after first event
		return nil
	})

	if err == nil {
		t.Error("expected context cancellation error, got nil")
	}
	if eventCount != 1 {
		t.Errorf("expected 1 event before cancellation, got %d", eventCount)
	}
}

func TestScanSSE_callbackError(t *testing.T) {
	input := "data: event 1\n\ndata: event 2\n\n"

	var events []SSEEvent
	testErr := fmt.Errorf("callback error")

	err := ScanSSE(context.Background(), strings.NewReader(input), func(e SSEEvent) error {
		events = append(events, e)
		if len(events) == 1 {
			return testErr
		}
		return nil
	})

	if err != testErr {
		t.Errorf("expected callback error, got: %v", err)
	}
	if len(events) != 1 {
		t.Errorf("expected 1 event before error, got %d", len(events))
	}
}

func TestScanSSE_withEventField(t *testing.T) {
	input := "event: custom\ndata: event data\n\n"

	var events []SSEEvent
	err := ScanSSE(context.Background(), strings.NewReader(input), func(e SSEEvent) error {
		events = append(events, e)
		return nil
	})

	if err != nil {
		t.Errorf("unexpected error: %v", err)
	}
	if len(events) != 1 {
		t.Errorf("expected 1 event, got %d", len(events))
	}
	if events[0].Event != "custom" {
		t.Errorf("expected event 'custom', got %q", events[0].Event)
	}
	if events[0].Data != "event data" {
		t.Errorf("expected data 'event data', got %q", events[0].Data)
	}
}
