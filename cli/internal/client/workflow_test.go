// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package client

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestStartWorkflow(t *testing.T) {
	tests := []struct {
		name              string
		sessionID         string
		workflowID        string
		responseBody      string
		expectError       bool
		expectExecutionID string
	}{
		{
			name:              "valid start",
			sessionID:         "session-1",
			workflowID:        "workflow-1",
			responseBody:      `{"data":{"executionId":"exec-123"}}`,
			expectError:       false,
			expectExecutionID: "exec-123",
		},
		{
			name:        "missing sessionID",
			sessionID:   "",
			workflowID:  "workflow-1",
			expectError: true,
		},
		{
			name:        "missing workflowID",
			sessionID:   "session-1",
			workflowID:  "",
			expectError: true,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if r.URL.Path != "/api/workflow/start" {
					t.Errorf("expected path /api/workflow/start, got %s", r.URL.Path)
				}
				if r.Method != "POST" {
					t.Errorf("expected POST, got %s", r.Method)
				}
				w.Header().Set("Content-Type", "application/json")
				w.WriteHeader(http.StatusOK)
				_, _ = w.Write([]byte(tt.responseBody))
			}))
			defer server.Close()

			c := NewClient(server.URL)
			resp, err := c.StartWorkflow(tt.sessionID, tt.workflowID, nil)

			if tt.expectError && err == nil {
				t.Fatal("expected error, got nil")
			}
			if !tt.expectError && err != nil {
				t.Fatalf("unexpected error: %v", err)
			}
			if !tt.expectError && resp.ExecutionID != tt.expectExecutionID {
				t.Errorf("expected executionID %s, got %s", tt.expectExecutionID, resp.ExecutionID)
			}
		})
	}
}

func TestStopWorkflow(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != "POST" {
			t.Errorf("expected POST, got %s", r.Method)
		}
		if r.URL.Path != "/api/workflow/session-1/workflow-1/stop" {
			t.Errorf("expected /api/workflow/session-1/workflow-1/stop, got %s", r.URL.Path)
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"data":{"executionIds":["exec-1","exec-2"]}}`))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	resp, err := c.StopWorkflow("session-1", "workflow-1", "")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(resp.ExecutionIDs) != 2 {
		t.Errorf("expected 2 execution IDs, got %d", len(resp.ExecutionIDs))
	}
}

func TestStopExecution(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != "POST" {
			t.Errorf("expected POST, got %s", r.Method)
		}
		if r.URL.Path != "/api/executions/exec-123/stop" {
			t.Errorf("expected /api/executions/exec-123/stop, got %s", r.URL.Path)
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"data":{"executionId":"exec-123"}}`))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	resp, err := c.StopExecution("exec-123")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if resp.ExecutionID != "exec-123" {
		t.Errorf("expected execution ID exec-123, got %s", resp.ExecutionID)
	}
}

func TestRestartExecution(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != "POST" {
			t.Errorf("expected POST, got %s", r.Method)
		}
		if r.URL.Path != "/api/executions/exec-123/restart" {
			t.Errorf("expected /api/executions/exec-123/restart, got %s", r.URL.Path)
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"data":{"executionId":"exec-456"}}`))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	resp, err := c.RestartExecution("exec-123")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if resp.ExecutionID != "exec-456" {
		t.Errorf("expected execution ID exec-456, got %s", resp.ExecutionID)
	}
}

func TestRestartFromNode(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != "POST" {
			t.Errorf("expected POST, got %s", r.Method)
		}
		if r.URL.Path != "/api/executions/exec-123/restart/node-1" {
			t.Errorf("expected /api/executions/exec-123/restart/node-1, got %s", r.URL.Path)
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"data":{"executionId":"exec-456"}}`))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	resp, err := c.RestartFromNode("exec-123", "node-1")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if resp.ExecutionID != "exec-456" {
		t.Errorf("expected execution ID exec-456, got %s", resp.ExecutionID)
	}
}

func TestPauseWorkflow(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != "POST" {
			t.Errorf("expected POST, got %s", r.Method)
		}
		if r.URL.Path != "/api/executions/exec-123/pause" {
			t.Errorf("expected /api/executions/exec-123/pause, got %s", r.URL.Path)
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"data":{"executionId":"exec-123"}}`))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	resp, err := c.PauseWorkflow("session-1", "exec-123")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if resp.ExecutionID != "exec-123" {
		t.Errorf("expected execution ID exec-123, got %s", resp.ExecutionID)
	}
}

func TestResumeWorkflow(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != "POST" {
			t.Errorf("expected POST, got %s", r.Method)
		}
		if r.URL.Path != "/api/executions/exec-123/resume" {
			t.Errorf("expected /api/executions/exec-123/resume, got %s", r.URL.Path)
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"data":{"executionId":"exec-123"}}`))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	resp, err := c.ResumeWorkflow("session-1", "exec-123")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if resp.ExecutionID != "exec-123" {
		t.Errorf("expected execution ID exec-123, got %s", resp.ExecutionID)
	}
}

func TestGetWorkflowStatus(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != "GET" {
			t.Errorf("expected GET, got %s", r.Method)
		}
		if r.URL.Path != "/api/executions/exec-123" {
			t.Errorf("expected /api/executions/exec-123, got %s", r.URL.Path)
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"data":{"executionId":"exec-123","sessionId":"session-1","workflowId":"wf-1","status":"RUNNING","tasks":[],"startTime":"2026-07-17T10:00:00Z","endTime":"0001-01-01T00:00:00Z"}}`))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	resp, err := c.GetWorkflowStatus("session-1", "exec-123")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if resp.ExecutionID != "exec-123" {
		t.Errorf("expected execution ID exec-123, got %s", resp.ExecutionID)
	}
	if resp.Status != "RUNNING" {
		t.Errorf("expected status RUNNING, got %s", resp.Status)
	}
}

func TestGetWorkflowStatus_runOfAnotherSession_isNotFound(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"data":{"executionId":"exec-123","sessionId":"session-2","workflowId":"wf-1","status":"RUNNING","tasks":[],"startTime":"2026-07-17T10:00:00Z","endTime":null}}`))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	_, err := c.GetWorkflowStatus("session-1", "exec-123")

	if err == nil || !strings.Contains(err.Error(), `execution "exec-123" not found in session "session-1"`) {
		t.Fatalf("expected a not-found error for the other session, got %v", err)
	}
}

func TestGetWorkflowHistory(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != "GET" {
			t.Errorf("expected GET, got %s", r.Method)
		}
		if r.URL.Path != "/api/workflow/session-1/history" {
			t.Errorf("expected /api/workflow/session-1/history, got %s", r.URL.Path)
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"data":[{"executionId":"exec-1","workflowId":"wf-1","status":"COMPLETED","startTime":"2026-07-17T10:00:00Z","endTime":"2026-07-17T10:05:00Z"}]}`))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	resp, err := c.GetWorkflowHistory("session-1")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(resp) != 1 {
		t.Fatalf("expected 1 summary, got %d", len(resp))
	}
	if resp[0].ExecutionID != "exec-1" {
		t.Errorf("expected execution ID exec-1, got %s", resp[0].ExecutionID)
	}
}

func TestPauseNode(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != "POST" {
			t.Errorf("expected POST, got %s", r.Method)
		}
		if r.URL.Path != "/api/executions/exec-123/nodes/node-1/pause" {
			t.Errorf("expected /api/executions/exec-123/nodes/node-1/pause, got %s", r.URL.Path)
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"data":{"executionId":"exec-123"}}`))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	resp, err := c.PauseNode("session-1", "exec-123", "node-1")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if resp.ExecutionID != "exec-123" {
		t.Errorf("expected execution ID exec-123, got %s", resp.ExecutionID)
	}
}

func TestResumeNode(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != "POST" {
			t.Errorf("expected POST, got %s", r.Method)
		}
		if r.URL.Path != "/api/executions/exec-123/nodes/node-1/resume" {
			t.Errorf("expected /api/executions/exec-123/nodes/node-1/resume, got %s", r.URL.Path)
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"data":{"executionId":"exec-123"}}`))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	resp, err := c.ResumeNode("session-1", "exec-123", "node-1")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if resp.ExecutionID != "exec-123" {
		t.Errorf("expected execution ID exec-123, got %s", resp.ExecutionID)
	}
}

func TestEnableStepMode(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != "POST" {
			t.Errorf("expected POST, got %s", r.Method)
		}
		if r.URL.Path != "/api/executions/exec-123/nodes/node-1/step/enable" {
			t.Errorf("expected /api/executions/exec-123/nodes/node-1/step/enable, got %s", r.URL.Path)
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"data":{"executionId":"exec-123"}}`))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	resp, err := c.EnableStepMode("session-1", "exec-123", "node-1")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if resp.ExecutionID != "exec-123" {
		t.Errorf("expected execution ID exec-123, got %s", resp.ExecutionID)
	}
}

func TestDisableStepMode(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != "POST" {
			t.Errorf("expected POST, got %s", r.Method)
		}
		if r.URL.Path != "/api/executions/exec-123/nodes/node-1/step/disable" {
			t.Errorf("expected /api/executions/exec-123/nodes/node-1/step/disable, got %s", r.URL.Path)
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"data":{"executionId":"exec-123"}}`))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	resp, err := c.DisableStepMode("session-1", "exec-123", "node-1")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if resp.ExecutionID != "exec-123" {
		t.Errorf("expected execution ID exec-123, got %s", resp.ExecutionID)
	}
}

func TestStepNode(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != "POST" {
			t.Errorf("expected POST, got %s", r.Method)
		}
		if r.URL.Path != "/api/executions/exec-123/nodes/node-1/step" {
			t.Errorf("expected /api/executions/exec-123/nodes/node-1/step, got %s", r.URL.Path)
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"data":{"executionId":"exec-123"}}`))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	resp, err := c.StepNode("session-1", "exec-123", "node-1")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if resp.ExecutionID != "exec-123" {
		t.Errorf("expected execution ID exec-123, got %s", resp.ExecutionID)
	}
}

func TestStopNode(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != "POST" {
			t.Errorf("expected POST, got %s", r.Method)
		}
		if r.URL.Path != "/api/executions/exec-123/nodes/node-1/stop" {
			t.Errorf("expected /api/executions/exec-123/nodes/node-1/stop, got %s", r.URL.Path)
		}
		if got := r.URL.Query().Get("immediate"); got != "true" {
			t.Errorf("expected immediate=true, got %q", got)
		}
		if got := r.URL.Query().Get("reason"); got != "test reason" {
			t.Errorf("expected reason=%q, got %q", "test reason", got)
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"data":{"executionId":"exec-123"}}`))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	resp, err := c.StopNode("session-1", "exec-123", "node-1", true, "test reason")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if resp.ExecutionID != "exec-123" {
		t.Errorf("expected execution ID exec-123, got %s", resp.ExecutionID)
	}
}

func TestSkipNode(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != "POST" {
			t.Errorf("expected POST, got %s", r.Method)
		}
		if r.URL.Path != "/api/executions/exec-123/nodes/node-1/skip" {
			t.Errorf("expected /api/executions/exec-123/nodes/node-1/skip, got %s", r.URL.Path)
		}
		if got := r.URL.Query().Get("skip"); got != "true" {
			t.Errorf("expected skip=true, got %q", got)
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"data":{"executionId":"exec-123"}}`))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	resp, err := c.SkipNode("session-1", "exec-123", "node-1", true)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if resp.ExecutionID != "exec-123" {
		t.Errorf("expected execution ID exec-123, got %s", resp.ExecutionID)
	}
}

func TestStreamWorkflowStatus(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if answerDone(w, r) {
			return
		}
		if r.Method != "GET" {
			t.Errorf("expected GET, got %s", r.Method)
		}
		if r.URL.Path != "/api/executions/exec-123/status/stream" {
			t.Errorf("expected /api/executions/exec-123/status/stream, got %s", r.URL.Path)
		}
		if got := r.URL.Query().Get("includeHistory"); got != "false" {
			t.Errorf("expected includeHistory=false, got %q", got)
		}
		w.Header().Set("Content-Type", "text/event-stream")
		w.WriteHeader(http.StatusOK)

		// Send a mock SSE event
		progress := WorkflowProgress{
			ExecutionID: "exec-123",
			SessionID:   "session-1",
			Status:      "RUNNING",
			Tasks:       []TaskProgress{},
		}
		data, _ := json.Marshal(progress)
		fmt.Fprintf(w, "data: %s\n\n", string(data))

		if f, ok := w.(http.Flusher); ok {
			f.Flush()
		}
	}))
	defer server.Close()

	c := NewClient(server.URL)
	called := false
	err := c.StreamWorkflowStatus(context.Background(), "session-1", "exec-123", false, func(progress WorkflowProgress) error {
		called = true
		if progress.ExecutionID != "exec-123" {
			t.Errorf("expected execution ID exec-123, got %s", progress.ExecutionID)
		}
		return nil
	})

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if !called {
		t.Error("onProgress callback was not called")
	}
}

// Error-path tests for workflow functions

// TestStartWorkflow_invalidJSON tests handling of invalid JSON in response
func TestStartWorkflow_invalidJSON(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"invalid json"`))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	_, err := c.StartWorkflow("session-1", "workflow-1", nil)

	if err == nil {
		t.Error("expected error for invalid JSON, got nil")
	}
	if err.Error() != "failed to unmarshal response: unexpected end of JSON input" {
		t.Errorf("expected unmarshal error, got: %v", err)
	}
}

// TestStartWorkflow_httpError tests handling of HTTP error responses
func TestStartWorkflow_httpError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
		_, _ = w.Write([]byte("Internal Server Error"))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	_, err := c.StartWorkflow("session-1", "workflow-1", nil)

	if err == nil {
		t.Error("expected error for 500 status, got nil")
	}
	if !contains(err.Error(), "API error") || !contains(err.Error(), "500") {
		t.Errorf("expected API error 500, got: %v", err)
	}
}

// TestStartWorkflow_networkError tests handling of network failures
// TestStopWorkflow_invalidJSON tests JSON unmarshaling error
func TestStopWorkflow_invalidJSON(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"data":"invalid"}`))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	_, err := c.StopWorkflow("session-1", "workflow-1", "")

	if err == nil {
		t.Error("expected error for invalid response format, got nil")
	}
}

// TestStopWorkflow_httpError tests HTTP error response
func TestStopWorkflow_httpError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNotFound)
		_, _ = w.Write([]byte("Workflow not found"))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	_, err := c.StopWorkflow("session-1", "nonexistent", "")

	if err == nil {
		t.Error("expected error for 404 status, got nil")
	}
}

// TestStopWorkflow_emptySessionID tests validation error
func TestStopWorkflow_emptySessionID(t *testing.T) {
	c := NewClient("http://localhost:8080")
	_, err := c.StopWorkflow("", "workflow-1", "")

	if err == nil {
		t.Error("expected error for empty sessionID, got nil")
	}
	if !contains(err.Error(), "sessionID cannot be empty") {
		t.Errorf("expected sessionID empty error, got: %v", err)
	}
}

// TestStopWorkflow_emptyWorkflowID tests validation error
func TestStopWorkflow_emptyWorkflowID(t *testing.T) {
	c := NewClient("http://localhost:8080")
	_, err := c.StopWorkflow("session-1", "", "")

	if err == nil {
		t.Error("expected error for empty workflowID, got nil")
	}
	if !contains(err.Error(), "workflowID cannot be empty") {
		t.Errorf("expected workflowID empty error, got: %v", err)
	}
}

// TestStopExecution_invalidJSON tests JSON unmarshaling error
func TestStopExecution_invalidJSON(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`malformed`))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	_, err := c.StopExecution("exec-123")

	if err == nil {
		t.Error("expected error for invalid JSON, got nil")
	}
}

// TestStopExecution_httpError tests HTTP error response
func TestStopExecution_httpError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusBadRequest)
		_, _ = w.Write([]byte("Invalid request"))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	_, err := c.StopExecution("exec-123")

	if err == nil {
		t.Error("expected error for 400 status, got nil")
	}
}

// TestStopExecution_emptyExecutionID tests validation error
func TestStopExecution_emptyExecutionID(t *testing.T) {
	c := NewClient("http://localhost:8080")
	_, err := c.StopExecution("")

	if err == nil {
		t.Error("expected error for empty executionID, got nil")
	}
	if !contains(err.Error(), "executionID cannot be empty") {
		t.Errorf("expected executionID empty error, got: %v", err)
	}
}

// TestRestartExecution_invalidJSON tests JSON unmarshaling error
func TestRestartExecution_invalidJSON(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"data":"not an object"}`))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	_, err := c.RestartExecution("exec-123")

	if err == nil {
		t.Error("expected error for invalid response format, got nil")
	}
}

// TestRestartExecution_httpError tests HTTP error response
func TestRestartExecution_httpError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusForbidden)
		_, _ = w.Write([]byte("Forbidden"))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	_, err := c.RestartExecution("exec-123")

	if err == nil {
		t.Error("expected error for 403 status, got nil")
	}
}

// TestRestartExecution_emptyExecutionID tests validation error
func TestRestartExecution_emptyExecutionID(t *testing.T) {
	c := NewClient("http://localhost:8080")
	_, err := c.RestartExecution("")

	if err == nil {
		t.Error("expected error for empty executionID, got nil")
	}
}

// TestRestartFromNode_emptyExecutionID tests validation error
func TestRestartFromNode_emptyExecutionID(t *testing.T) {
	c := NewClient("http://localhost:8080")
	_, err := c.RestartFromNode("", "node-1")

	if err == nil {
		t.Error("expected error for empty executionID, got nil")
	}
}

// TestRestartFromNode_emptyNodeID tests validation error
func TestRestartFromNode_emptyNodeID(t *testing.T) {
	c := NewClient("http://localhost:8080")
	_, err := c.RestartFromNode("exec-123", "")

	if err == nil {
		t.Error("expected error for empty nodeID, got nil")
	}
}

// TestRestartFromNode_httpError tests HTTP error response
func TestRestartFromNode_httpError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNotFound)
		_, _ = w.Write([]byte("Node not found"))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	_, err := c.RestartFromNode("exec-123", "invalid-node")

	if err == nil {
		t.Error("expected error for 404 status, got nil")
	}
}

// TestPauseWorkflow_emptySessionID tests validation error
func TestPauseWorkflow_emptySessionID(t *testing.T) {
	c := NewClient("http://localhost:8080")
	_, err := c.PauseWorkflow("", "exec-123")

	if err == nil {
		t.Error("expected error for empty sessionID, got nil")
	}
}

// TestPauseWorkflow_emptyExecutionID tests validation error
func TestPauseWorkflow_emptyExecutionID(t *testing.T) {
	c := NewClient("http://localhost:8080")
	_, err := c.PauseWorkflow("session-1", "")

	if err == nil {
		t.Error("expected error for empty executionID, got nil")
	}
}

// TestPauseWorkflow_httpError tests HTTP error response
func TestPauseWorkflow_httpError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusConflict)
		_, _ = w.Write([]byte("Conflict"))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	_, err := c.PauseWorkflow("session-1", "exec-123")

	if err == nil {
		t.Error("expected error for 409 status, got nil")
	}
}

// TestResumeWorkflow_emptySessionID tests validation error
func TestResumeWorkflow_emptySessionID(t *testing.T) {
	c := NewClient("http://localhost:8080")
	_, err := c.ResumeWorkflow("", "exec-123")

	if err == nil {
		t.Error("expected error for empty sessionID, got nil")
	}
}

// TestResumeWorkflow_emptyExecutionID tests validation error
func TestResumeWorkflow_emptyExecutionID(t *testing.T) {
	c := NewClient("http://localhost:8080")
	_, err := c.ResumeWorkflow("session-1", "")

	if err == nil {
		t.Error("expected error for empty executionID, got nil")
	}
}

// TestResumeWorkflow_httpError tests HTTP error response
func TestResumeWorkflow_httpError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusServiceUnavailable)
		_, _ = w.Write([]byte("Service Unavailable"))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	_, err := c.ResumeWorkflow("session-1", "exec-123")

	if err == nil {
		t.Error("expected error for 503 status, got nil")
	}
}

// TestGetWorkflowStatus_emptySessionID tests validation error
func TestGetWorkflowStatus_emptySessionID(t *testing.T) {
	c := NewClient("http://localhost:8080")
	_, err := c.GetWorkflowStatus("", "exec-123")

	if err == nil {
		t.Error("expected error for empty sessionID, got nil")
	}
}

// TestGetWorkflowStatus_emptyExecutionID tests validation error
func TestGetWorkflowStatus_emptyExecutionID(t *testing.T) {
	c := NewClient("http://localhost:8080")
	_, err := c.GetWorkflowStatus("session-1", "")

	if err == nil {
		t.Error("expected error for empty executionID, got nil")
	}
}

// TestGetWorkflowStatus_invalidJSON tests JSON unmarshaling error
func TestGetWorkflowStatus_invalidJSON(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{invalid`))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	_, err := c.GetWorkflowStatus("session-1", "exec-123")

	if err == nil {
		t.Error("expected error for invalid JSON, got nil")
	}
}

// TestGetWorkflowStatus_httpError tests HTTP error response
func TestGetWorkflowStatus_httpError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusGatewayTimeout)
		_, _ = w.Write([]byte("Gateway Timeout"))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	_, err := c.GetWorkflowStatus("session-1", "exec-123")

	if err == nil {
		t.Error("expected error for 504 status, got nil")
	}
}

// TestGetWorkflowHistory_emptySessionID tests validation error
func TestGetWorkflowHistory_emptySessionID(t *testing.T) {
	c := NewClient("http://localhost:8080")
	_, err := c.GetWorkflowHistory("")

	if err == nil {
		t.Error("expected error for empty sessionID, got nil")
	}
}

// TestGetWorkflowHistory_invalidJSON tests JSON unmarshaling error
func TestGetWorkflowHistory_invalidJSON(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"data":"not an array"}`))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	_, err := c.GetWorkflowHistory("session-1")

	if err == nil {
		t.Error("expected error for invalid response format, got nil")
	}
}

// TestGetWorkflowHistory_httpError tests HTTP error response
func TestGetWorkflowHistory_httpError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusUnauthorized)
		_, _ = w.Write([]byte("Unauthorized"))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	_, err := c.GetWorkflowHistory("session-1")

	if err == nil {
		t.Error("expected error for 401 status, got nil")
	}
}

// TestPauseNode_emptySessionID tests validation error
func TestPauseNode_emptySessionID(t *testing.T) {
	c := NewClient("http://localhost:8080")
	_, err := c.PauseNode("", "exec-123", "node-1")

	if err == nil {
		t.Error("expected error for empty sessionID, got nil")
	}
}

// TestPauseNode_emptyExecutionID tests validation error
func TestPauseNode_emptyExecutionID(t *testing.T) {
	c := NewClient("http://localhost:8080")
	_, err := c.PauseNode("session-1", "", "node-1")

	if err == nil {
		t.Error("expected error for empty executionID, got nil")
	}
}

// TestPauseNode_emptyNodeID tests validation error
func TestPauseNode_emptyNodeID(t *testing.T) {
	c := NewClient("http://localhost:8080")
	_, err := c.PauseNode("session-1", "exec-123", "")

	if err == nil {
		t.Error("expected error for empty nodeID, got nil")
	}
}

// TestPauseNode_httpError tests HTTP error response
func TestPauseNode_httpError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusBadGateway)
		_, _ = w.Write([]byte("Bad Gateway"))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	_, err := c.PauseNode("session-1", "exec-123", "node-1")

	if err == nil {
		t.Error("expected error for 502 status, got nil")
	}
}

// TestResumeNode_emptySessionID tests validation error
func TestResumeNode_emptySessionID(t *testing.T) {
	c := NewClient("http://localhost:8080")
	_, err := c.ResumeNode("", "exec-123", "node-1")

	if err == nil {
		t.Error("expected error for empty sessionID, got nil")
	}
}

// TestResumeNode_emptyExecutionID tests validation error
func TestResumeNode_emptyExecutionID(t *testing.T) {
	c := NewClient("http://localhost:8080")
	_, err := c.ResumeNode("session-1", "", "node-1")

	if err == nil {
		t.Error("expected error for empty executionID, got nil")
	}
}

// TestResumeNode_emptyNodeID tests validation error
func TestResumeNode_emptyNodeID(t *testing.T) {
	c := NewClient("http://localhost:8080")
	_, err := c.ResumeNode("session-1", "exec-123", "")

	if err == nil {
		t.Error("expected error for empty nodeID, got nil")
	}
}

// TestResumeNode_httpError tests HTTP error response
func TestResumeNode_httpError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusTooManyRequests)
		_, _ = w.Write([]byte("Too Many Requests"))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	_, err := c.ResumeNode("session-1", "exec-123", "node-1")

	if err == nil {
		t.Error("expected error for 429 status, got nil")
	}
}

// TestEnableStepMode_emptySessionID tests validation error
func TestEnableStepMode_emptySessionID(t *testing.T) {
	c := NewClient("http://localhost:8080")
	_, err := c.EnableStepMode("", "exec-123", "node-1")

	if err == nil {
		t.Error("expected error for empty sessionID, got nil")
	}
}

// TestEnableStepMode_emptyExecutionID tests validation error
func TestEnableStepMode_emptyExecutionID(t *testing.T) {
	c := NewClient("http://localhost:8080")
	_, err := c.EnableStepMode("session-1", "", "node-1")

	if err == nil {
		t.Error("expected error for empty executionID, got nil")
	}
}

// TestEnableStepMode_emptyNodeID tests validation error
func TestEnableStepMode_emptyNodeID(t *testing.T) {
	c := NewClient("http://localhost:8080")
	_, err := c.EnableStepMode("session-1", "exec-123", "")

	if err == nil {
		t.Error("expected error for empty nodeID, got nil")
	}
}

// TestEnableStepMode_httpError tests HTTP error response
func TestEnableStepMode_httpError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusPreconditionFailed)
		_, _ = w.Write([]byte("Precondition Failed"))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	_, err := c.EnableStepMode("session-1", "exec-123", "node-1")

	if err == nil {
		t.Error("expected error for 412 status, got nil")
	}
}

// TestDisableStepMode_emptySessionID tests validation error
func TestDisableStepMode_emptySessionID(t *testing.T) {
	c := NewClient("http://localhost:8080")
	_, err := c.DisableStepMode("", "exec-123", "node-1")

	if err == nil {
		t.Error("expected error for empty sessionID, got nil")
	}
}

// TestDisableStepMode_emptyExecutionID tests validation error
func TestDisableStepMode_emptyExecutionID(t *testing.T) {
	c := NewClient("http://localhost:8080")
	_, err := c.DisableStepMode("session-1", "", "node-1")

	if err == nil {
		t.Error("expected error for empty executionID, got nil")
	}
}

// TestDisableStepMode_emptyNodeID tests validation error
func TestDisableStepMode_emptyNodeID(t *testing.T) {
	c := NewClient("http://localhost:8080")
	_, err := c.DisableStepMode("session-1", "exec-123", "")

	if err == nil {
		t.Error("expected error for empty nodeID, got nil")
	}
}

// TestDisableStepMode_httpError tests HTTP error response
func TestDisableStepMode_httpError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusRequestTimeout)
		_, _ = w.Write([]byte("Request Timeout"))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	_, err := c.DisableStepMode("session-1", "exec-123", "node-1")

	if err == nil {
		t.Error("expected error for 408 status, got nil")
	}
}

// TestStepNode_emptySessionID tests validation error
func TestStepNode_emptySessionID(t *testing.T) {
	c := NewClient("http://localhost:8080")
	_, err := c.StepNode("", "exec-123", "node-1")

	if err == nil {
		t.Error("expected error for empty sessionID, got nil")
	}
}

// TestStepNode_emptyExecutionID tests validation error
func TestStepNode_emptyExecutionID(t *testing.T) {
	c := NewClient("http://localhost:8080")
	_, err := c.StepNode("session-1", "", "node-1")

	if err == nil {
		t.Error("expected error for empty executionID, got nil")
	}
}

// TestStepNode_emptyNodeID tests validation error
func TestStepNode_emptyNodeID(t *testing.T) {
	c := NewClient("http://localhost:8080")
	_, err := c.StepNode("session-1", "exec-123", "")

	if err == nil {
		t.Error("expected error for empty nodeID, got nil")
	}
}

// TestStepNode_httpError tests HTTP error response
func TestStepNode_httpError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
		_, _ = w.Write([]byte("Internal Server Error"))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	_, err := c.StepNode("session-1", "exec-123", "node-1")

	if err == nil {
		t.Error("expected error for 500 status, got nil")
	}
}

// TestStopNode_emptySessionID tests validation error
func TestStopNode_noReasonSendsNoReasonParameter(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if _, present := r.URL.Query()["reason"]; present {
			t.Errorf("expected no reason parameter, got %q", r.URL.RawQuery)
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"data":{"executionId":"exec-123"}}`))
	}))
	defer server.Close()

	if _, err := NewClient(server.URL).StopNode("s", "exec-123", "node-1", false, ""); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
}

func TestStopNode_emptySessionID(t *testing.T) {
	c := NewClient("http://localhost:8080")
	_, err := c.StopNode("", "exec-123", "node-1", true, "test")

	if err == nil {
		t.Error("expected error for empty sessionID, got nil")
	}
}

// TestStopNode_emptyExecutionID tests validation error
func TestStopNode_emptyExecutionID(t *testing.T) {
	c := NewClient("http://localhost:8080")
	_, err := c.StopNode("session-1", "", "node-1", true, "test")

	if err == nil {
		t.Error("expected error for empty executionID, got nil")
	}
}

// TestStopNode_emptyNodeID tests validation error
func TestStopNode_emptyNodeID(t *testing.T) {
	c := NewClient("http://localhost:8080")
	_, err := c.StopNode("session-1", "exec-123", "", true, "test")

	if err == nil {
		t.Error("expected error for empty nodeID, got nil")
	}
}

// TestStopNode_httpError tests HTTP error response
func TestStopNode_httpError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNotFound)
		_, _ = w.Write([]byte("Not Found"))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	_, err := c.StopNode("session-1", "exec-123", "node-1", false, "reason")

	if err == nil {
		t.Error("expected error for 404 status, got nil")
	}
}

// TestSkipNode_emptySessionID tests validation error
func TestSkipNode_emptySessionID(t *testing.T) {
	c := NewClient("http://localhost:8080")
	_, err := c.SkipNode("", "exec-123", "node-1", true)

	if err == nil {
		t.Error("expected error for empty sessionID, got nil")
	}
}

// TestSkipNode_emptyExecutionID tests validation error
func TestSkipNode_emptyExecutionID(t *testing.T) {
	c := NewClient("http://localhost:8080")
	_, err := c.SkipNode("session-1", "", "node-1", true)

	if err == nil {
		t.Error("expected error for empty executionID, got nil")
	}
}

// TestSkipNode_emptyNodeID tests validation error
func TestSkipNode_emptyNodeID(t *testing.T) {
	c := NewClient("http://localhost:8080")
	_, err := c.SkipNode("session-1", "exec-123", "", true)

	if err == nil {
		t.Error("expected error for empty nodeID, got nil")
	}
}

// TestSkipNode_httpError tests HTTP error response
func TestSkipNode_httpError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusConflict)
		_, _ = w.Write([]byte("Conflict"))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	_, err := c.SkipNode("session-1", "exec-123", "node-1", false)

	if err == nil {
		t.Error("expected error for 409 status, got nil")
	}
}

// TestStreamWorkflowStatus_emptySessionID tests validation error
func TestStreamWorkflowStatus_emptySessionID(t *testing.T) {
	c := NewClient("http://localhost:8080")
	err := c.StreamWorkflowStatus(context.Background(), "", "exec-123", false, func(progress WorkflowProgress) error {
		return nil
	})

	if err == nil {
		t.Error("expected error for empty sessionID, got nil")
	}
}

// TestStreamWorkflowStatus_emptyExecutionID tests validation error
func TestStreamWorkflowStatus_emptyExecutionID(t *testing.T) {
	c := NewClient("http://localhost:8080")
	err := c.StreamWorkflowStatus(context.Background(), "session-1", "", false, func(progress WorkflowProgress) error {
		return nil
	})

	if err == nil {
		t.Error("expected error for empty executionID, got nil")
	}
}

// TestStreamWorkflowStatus_nilOnProgress tests validation error for a nil callback
func TestStreamWorkflowStatus_nilOnProgress(t *testing.T) {
	c := NewClient("http://localhost:8080")
	err := c.StreamWorkflowStatus(context.Background(), "session-1", "exec-123", false, nil)

	if err == nil {
		t.Fatal("expected error for nil onProgress, got nil")
	}
	if !strings.Contains(err.Error(), "nil") {
		t.Errorf("expected nil callback error message, got: %v", err)
	}
}

// TestStreamWorkflowStatus_httpError tests HTTP error response
func TestStreamWorkflowStatus_httpError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusForbidden)
		_, _ = w.Write([]byte("Forbidden"))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	err := c.StreamWorkflowStatus(context.Background(), "session-1", "exec-123", false, func(progress WorkflowProgress) error {
		return nil
	})

	if err == nil {
		t.Error("expected error for 403 status, got nil")
	}
}

// TestStreamWorkflowStatus_invalidJSON tests JSON unmarshaling error in streaming
func TestStreamWorkflowStatus_invalidJSON(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/event-stream")
		w.WriteHeader(http.StatusOK)
		fmt.Fprintf(w, "data: {invalid json\n\n")
		if f, ok := w.(http.Flusher); ok {
			f.Flush()
		}
	}))
	defer server.Close()

	c := NewClient(server.URL)
	err := c.StreamWorkflowStatus(context.Background(), "session-1", "exec-123", false, func(progress WorkflowProgress) error {
		return nil
	})

	if err == nil {
		t.Error("expected error for invalid JSON in stream, got nil")
	}
}

// TestStreamWorkflowStatus_callbackError tests callback returning error
func TestStreamWorkflowStatus_callbackError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/event-stream")
		w.WriteHeader(http.StatusOK)

		progress := WorkflowProgress{
			ExecutionID: "exec-123",
			SessionID:   "session-1",
			Status:      "RUNNING",
			Tasks:       []TaskProgress{},
		}
		data, _ := json.Marshal(progress)
		fmt.Fprintf(w, "data: %s\n\n", string(data))

		if f, ok := w.(http.Flusher); ok {
			f.Flush()
		}
	}))
	defer server.Close()

	c := NewClient(server.URL)
	testErr := fmt.Errorf("callback error")
	err := c.StreamWorkflowStatus(context.Background(), "session-1", "exec-123", false, func(progress WorkflowProgress) error {
		return testErr
	})

	if err != testErr {
		t.Errorf("expected callback error, got: %v", err)
	}
}

// TestStopExecution_networkError tests network failure handling
// TestRestartExecution_networkError tests network failure handling
// TestRestartFromNode_networkError tests network failure handling
// TestRestartFromNode_invalidJSON tests JSON unmarshaling error
func TestRestartFromNode_invalidJSON(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"data":123}`))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	_, err := c.RestartFromNode("exec-123", "node-1")

	if err == nil {
		t.Error("expected error for invalid response format, got nil")
	}
}

// TestPauseWorkflow_networkError tests network failure handling
// TestPauseWorkflow_invalidJSON tests JSON unmarshaling error
func TestPauseWorkflow_invalidJSON(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`not json`))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	_, err := c.PauseWorkflow("session-1", "exec-123")

	if err == nil {
		t.Error("expected error for invalid JSON, got nil")
	}
}

// TestResumeWorkflow_networkError tests network failure handling
// TestResumeWorkflow_invalidJSON tests JSON unmarshaling error
func TestResumeWorkflow_invalidJSON(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"data":[]}`))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	_, err := c.ResumeWorkflow("session-1", "exec-123")

	if err == nil {
		t.Error("expected error for invalid response format, got nil")
	}
}

// TestGetWorkflowStatus_networkError tests network failure handling
// TestGetWorkflowHistory_networkError tests network failure handling
// TestPauseNode_networkError tests network failure handling
// TestPauseNode_invalidJSON tests JSON unmarshaling error
func TestPauseNode_invalidJSON(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"data":"invalid type"}`))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	_, err := c.PauseNode("session-1", "exec-123", "node-1")

	if err == nil {
		t.Error("expected error for invalid response format, got nil")
	}
}

// TestResumeNode_networkError tests network failure handling
// TestResumeNode_invalidJSON tests JSON unmarshaling error
func TestResumeNode_invalidJSON(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{`))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	_, err := c.ResumeNode("session-1", "exec-123", "node-1")

	if err == nil {
		t.Error("expected error for invalid JSON, got nil")
	}
}

// TestEnableStepMode_networkError tests network failure handling
// TestEnableStepMode_invalidJSON tests JSON unmarshaling error
func TestEnableStepMode_invalidJSON(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"data":{}`))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	_, err := c.EnableStepMode("session-1", "exec-123", "node-1")

	if err == nil {
		t.Error("expected error for invalid JSON, got nil")
	}
}

// TestDisableStepMode_networkError tests network failure handling
// TestDisableStepMode_invalidJSON tests JSON unmarshaling error
func TestDisableStepMode_invalidJSON(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"data":"string"}`))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	_, err := c.DisableStepMode("session-1", "exec-123", "node-1")

	if err == nil {
		t.Error("expected error for invalid response format, got nil")
	}
}

// TestStepNode_networkError tests network failure handling
// TestStepNode_invalidJSON tests JSON unmarshaling error
func TestStepNode_invalidJSON(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`]]`))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	_, err := c.StepNode("session-1", "exec-123", "node-1")

	if err == nil {
		t.Error("expected error for invalid JSON, got nil")
	}
}

// TestStopNode_networkError tests network failure handling
// TestStopNode_invalidJSON tests JSON unmarshaling error
func TestStopNode_invalidJSON(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"data":["array","not","object"]}`))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	_, err := c.StopNode("session-1", "exec-123", "node-1", true, "test")

	if err == nil {
		t.Error("expected error for invalid response format, got nil")
	}
}

// TestSkipNode_networkError tests network failure handling
// TestSkipNode_invalidJSON tests JSON unmarshaling error
func TestSkipNode_invalidJSON(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"data":0}`))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	_, err := c.SkipNode("session-1", "exec-123", "node-1", true)

	if err == nil {
		t.Error("expected error for invalid response format, got nil")
	}
}

// TestStreamWorkflowStatus_networkError tests network failure handling
// Helper function to check if a string contains a substring
func contains(s, substr string) bool {
	return strings.Contains(s, substr)
}

// ===== RequestFactory Failure Tests (Complete Coverage) =====

// TestStartWorkflow_requestFactoryFails_returnsError tests newRequestWithBody error path in StartWorkflow.
// TestStopWorkflow_requestFactoryFails_returnsError tests newRequest error path in StopWorkflow.
// TestStopExecution_requestFactoryFails_returnsError tests newRequest error path in StopExecution.
// TestRestartExecution_requestFactoryFails_returnsError tests newRequest error path in RestartExecution.
// TestRestartFromNode_requestFactoryFails_returnsError tests newRequest error path in RestartFromNode.
// TestPauseWorkflow_requestFactoryFails_returnsError tests newRequest error path in PauseWorkflow.
// TestResumeWorkflow_requestFactoryFails_returnsError tests newRequest error path in ResumeWorkflow.
// TestGetWorkflowStatus_requestFactoryFails_returnsError tests newRequest error path in GetWorkflowStatus.
// TestGetWorkflowHistory_requestFactoryFails_returnsError tests newRequest error path in GetWorkflowHistory.
// TestPauseNode_requestFactoryFails_returnsError tests newRequest error path in PauseNode.
// TestResumeNode_requestFactoryFails_returnsError tests newRequest error path in ResumeNode.
// TestEnableStepMode_requestFactoryFails_returnsError tests newRequest error path in EnableStepMode.
// TestDisableStepMode_requestFactoryFails_returnsError tests newRequest error path in DisableStepMode.
// TestStepNode_requestFactoryFails_returnsError tests newRequest error path in StepNode.
// TestStopNode_requestFactoryFails_returnsError tests newRequest error path in StopNode.
// TestSkipNode_requestFactoryFails_returnsError tests newRequest error path in SkipNode.
func TestStartWorkflow_sendsPayload(t *testing.T) {
	var sent map[string]any
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		sent = nil
		_ = json.NewDecoder(r.Body).Decode(&sent)
		_, _ = w.Write([]byte(`{"data":{"executionId":"e"}}`))
	}))
	defer srv.Close()

	if _, err := NewClient(srv.URL).StartWorkflow("s", "w", map[string]any{"k": "v"}); err != nil {
		t.Fatal(err)
	}
	if sent["payload"].(map[string]any)["k"] != "v" {
		t.Errorf("payload missing from body: %v", sent)
	}
	if _, err := NewClient(srv.URL).StartWorkflow("s", "w", nil); err != nil {
		t.Fatal(err)
	}
	if _, ok := sent["payload"]; ok {
		t.Errorf("empty payload should be omitted: %v", sent)
	}
}

func TestStartWorkflowKeyed_sendsTheIdempotencyKeyHeaderOnlyWhenGiven(t *testing.T) {
	var header string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		header = r.Header.Get("Idempotency-Key")
		_, _ = w.Write([]byte(`{"data":{"executionId":"e1"}}`))
	}))
	defer srv.Close()
	c := NewClient(srv.URL)

	if _, err := c.StartWorkflowKeyed("s", "w", nil, "k-1"); err != nil || header != "k-1" {
		t.Fatalf("header=%q err=%v", header, err)
	}
	if _, err := c.StartWorkflow("s", "w", nil); err != nil || header != "" {
		t.Fatalf("header=%q err=%v", header, err)
	}
}

func TestStopWorkflow_modeIsSentUppercased(t *testing.T) {
	var got string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		got = r.URL.RawQuery
		_, _ = w.Write([]byte(`{"data":{"executionIds":[]}}`))
	}))
	defer server.Close()

	if _, err := NewClient(server.URL).StopWorkflow("s", "w", "kill"); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if got != "mode=KILL" {
		t.Errorf("expected mode=KILL, got %q", got)
	}
}
