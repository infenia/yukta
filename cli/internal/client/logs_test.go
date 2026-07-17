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

func TestStreamExecutionLogs_success(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != "GET" {
			t.Errorf("expected GET, got %s", r.Method)
		}
		if r.URL.Path != "/api/sessions/session-123/executions/exec-456/logs" {
			t.Errorf("expected /api/sessions/session-123/executions/exec-456/logs, got %s", r.URL.Path)
		}

		w.Header().Set("Content-Type", "text/event-stream")
		flusher := w.(http.Flusher)

		fmt.Fprint(w, "data: Log line 1\n\n")
		flusher.Flush()

		fmt.Fprint(w, "data: Log line 2\n\n")
		flusher.Flush()

		fmt.Fprint(w, "data: Log line 3\n\n")
		flusher.Flush()
	}))
	defer server.Close()

	c := NewClient(server.URL)

	var receivedLines []string
	err := c.StreamExecutionLogs(
		context.Background(),
		"session-123",
		"exec-456",
		func(line string) error {
			receivedLines = append(receivedLines, line)
			return nil
		},
	)

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	if len(receivedLines) != 3 {
		t.Fatalf("expected 3 lines, got %d", len(receivedLines))
	}
	if receivedLines[0] != "Log line 1" {
		t.Errorf("expected 'Log line 1', got %q", receivedLines[0])
	}
	if receivedLines[1] != "Log line 2" {
		t.Errorf("expected 'Log line 2', got %q", receivedLines[1])
	}
	if receivedLines[2] != "Log line 3" {
		t.Errorf("expected 'Log line 3', got %q", receivedLines[2])
	}
}

func TestStreamExecutionLogs_emptySessionID(t *testing.T) {
	c := NewClient("http://localhost:8080")
	err := c.StreamExecutionLogs(context.Background(), "", "exec-456", func(line string) error {
		return nil
	})

	if err == nil {
		t.Error("expected error for empty sessionID, got nil")
	}
	if !strings.Contains(err.Error(), "empty") {
		t.Errorf("expected empty error message, got: %v", err)
	}
}

func TestStreamExecutionLogs_emptyExecutionID(t *testing.T) {
	c := NewClient("http://localhost:8080")
	err := c.StreamExecutionLogs(context.Background(), "session-123", "", func(line string) error {
		return nil
	})

	if err == nil {
		t.Error("expected error for empty executionID, got nil")
	}
	if !strings.Contains(err.Error(), "empty") {
		t.Errorf("expected empty error message, got: %v", err)
	}
}

func TestStreamExecutionLogs_nilOnLine(t *testing.T) {
	c := NewClient("http://localhost:8080")
	err := c.StreamExecutionLogs(context.Background(), "session-123", "exec-456", nil)

	if err == nil {
		t.Fatal("expected error for nil onLine, got nil")
	}
	if !strings.Contains(err.Error(), "nil") {
		t.Errorf("expected nil callback error message, got: %v", err)
	}
}

func TestStreamExecutionLogs_apiError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNotFound)
		_, _ = w.Write([]byte("Execution not found"))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	err := c.StreamExecutionLogs(context.Background(), "session-123", "nonexistent", func(line string) error {
		return nil
	})

	if err == nil {
		t.Error("expected error, got nil")
	}
	if !strings.Contains(err.Error(), "API error") || !strings.Contains(err.Error(), "404") {
		t.Errorf("expected 404 API error, got: %v", err)
	}
}

func TestStreamExecutionLogs_callbackError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/event-stream")
		flusher := w.(http.Flusher)

		fmt.Fprint(w, "data: Log line 1\n\n")
		flusher.Flush()

		fmt.Fprint(w, "data: Log line 2\n\n")
		flusher.Flush()
	}))
	defer server.Close()

	c := NewClient(server.URL)

	lineCount := 0
	callbackErr := fmt.Errorf("callback error")

	err := c.StreamExecutionLogs(
		context.Background(),
		"session-123",
		"exec-456",
		func(line string) error {
			lineCount++
			if lineCount == 1 {
				return callbackErr
			}
			return nil
		},
	)

	if err != callbackErr {
		t.Errorf("expected callback error, got: %v", err)
	}
	if lineCount != 1 {
		t.Errorf("expected 1 line before error, got %d", lineCount)
	}
}

func TestStreamExecutionLogs_contextCancellation(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/event-stream")
		flusher := w.(http.Flusher)

		fmt.Fprint(w, "data: Log line 1\n\n")
		flusher.Flush()

		fmt.Fprint(w, "data: Log line 2\n\n")
		flusher.Flush()
	}))
	defer server.Close()

	c := NewClient(server.URL)

	ctx, cancel := context.WithCancel(context.Background())
	lineCount := 0

	err := c.StreamExecutionLogs(
		ctx,
		"session-123",
		"exec-456",
		func(line string) error {
			lineCount++
			cancel() // Cancel after first line
			return nil
		},
	)

	if err == nil {
		t.Error("expected context cancellation error, got nil")
	}
	if lineCount != 1 {
		t.Errorf("expected 1 line before cancellation, got %d", lineCount)
	}
}
