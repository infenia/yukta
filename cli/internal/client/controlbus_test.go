// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package client

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestSendCommand_success(t *testing.T) {
	expectedResponse := map[string]interface{}{
		"status": "success",
		"result": "Command executed",
	}

	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != "POST" {
			t.Errorf("expected POST, got %s", r.Method)
		}
		if r.URL.Path != "/api/control/workflows/workflow-123/nodes/node-1/command" {
			t.Errorf("expected /api/control/workflows/workflow-123/nodes/node-1/command, got %s", r.URL.Path)
		}

		response := map[string]interface{}{
			"data": expectedResponse,
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(response)
	}))
	defer server.Close()

	c := NewClient(server.URL)
	commandPayload := []byte(`{"action":"pause"}`)
	result, err := c.SendCommand("s1", "workflow-123", "", "node-1", commandPayload)

	if err != nil {
		t.Errorf("unexpected error: %v", err)
	}
	if result["status"] != "success" {
		t.Errorf("expected status 'success', got %v", result["status"])
	}
}

func TestSendCommand_emptyPayload(t *testing.T) {
	c := NewClient("http://localhost:9858")
	_, err := c.SendCommand("s1", "workflow-123", "", "node-1", []byte{})

	if err == nil {
		t.Fatal("expected error for empty payload, got nil")
	}
	if !strings.Contains(err.Error(), "empty") {
		t.Errorf("expected empty error message, got: %v", err)
	}
}

func TestSendCommand_emptyWorkflowID(t *testing.T) {
	c := NewClient("http://localhost:9858")
	_, err := c.SendCommand("s1", "", "", "node-1", []byte(`{"action":"pause"}`))

	if err == nil {
		t.Fatal("expected error for empty workflowID, got nil")
	}
	if !strings.Contains(err.Error(), "workflowID") {
		t.Errorf("expected workflowID error message, got: %v", err)
	}
}

func TestSendCommand_emptyNodeID(t *testing.T) {
	c := NewClient("http://localhost:9858")
	_, err := c.SendCommand("s1", "workflow-123", "", "", []byte(`{"action":"pause"}`))

	if err == nil {
		t.Fatal("expected error for empty nodeID, got nil")
	}
	if !strings.Contains(err.Error(), "nodeID") {
		t.Errorf("expected nodeID error message, got: %v", err)
	}
}

func TestSendCommand_apiError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNotFound)
		_, _ = w.Write([]byte("Node not found"))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	_, err := c.SendCommand("s1", "workflow-123", "", "nonexistent", []byte(`{"action":"pause"}`))

	if err == nil {
		t.Fatal("expected error, got nil")
	}
	if !strings.Contains(err.Error(), "API error") || !strings.Contains(err.Error(), "404") {
		t.Errorf("expected 404 API error, got: %v", err)
	}
}

// ===== Malformed JSON Tests (Complete Coverage) =====

func TestSendCommand_emptySessionID(t *testing.T) {
	_, err := NewClient("http://localhost:9858").SendCommand("", "wf", "", "n", []byte(`{}`))
	if err == nil || !strings.Contains(err.Error(), "sessionID") {
		t.Fatalf("expected sessionID error, got %v", err)
	}
}

