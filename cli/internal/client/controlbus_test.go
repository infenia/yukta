// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package client

import (
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestGetActiveNodesInWorkflow_success(t *testing.T) {
	expectedNodes := []string{"node-1", "node-2", "node-3"}

	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != "GET" {
			t.Errorf("expected GET, got %s", r.Method)
		}
		if r.URL.Path != "/api/control/workflows/workflow-123/nodes" {
			t.Errorf("expected /api/control/workflows/workflow-123/nodes, got %s", r.URL.Path)
		}

		response := map[string]interface{}{
			"data": expectedNodes,
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(response)
	}))
	defer server.Close()

	c := NewClient(server.URL)
	nodes, err := c.GetActiveNodesInWorkflow("workflow-123")

	if err != nil {
		t.Errorf("unexpected error: %v", err)
	}
	if len(nodes) != len(expectedNodes) {
		t.Fatalf("expected %d nodes, got %d", len(expectedNodes), len(nodes))
	}
	for i, node := range nodes {
		if node != expectedNodes[i] {
			t.Errorf("node mismatch at index %d: expected %s, got %s", i, expectedNodes[i], node)
		}
	}
}

func TestGetActiveNodesInWorkflow_emptyWorkflowID(t *testing.T) {
	c := NewClient("http://localhost:8080")
	_, err := c.GetActiveNodesInWorkflow("")

	if err == nil {
		t.Fatal("expected error for empty workflowID, got nil")
	}
	if !strings.Contains(err.Error(), "empty") {
		t.Errorf("expected empty error message, got: %v", err)
	}
}

func TestGetAllActiveNodes_success(t *testing.T) {
	expectedNodes := []string{"global-node-1", "global-node-2"}

	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != "GET" {
			t.Errorf("expected GET, got %s", r.Method)
		}
		if r.URL.Path != "/api/control/nodes" {
			t.Errorf("expected /api/control/nodes, got %s", r.URL.Path)
		}

		response := map[string]interface{}{
			"data": expectedNodes,
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(response)
	}))
	defer server.Close()

	c := NewClient(server.URL)
	nodes, err := c.GetAllActiveNodes()

	if err != nil {
		t.Errorf("unexpected error: %v", err)
	}
	if len(nodes) != len(expectedNodes) {
		t.Errorf("expected %d nodes, got %d", len(expectedNodes), len(nodes))
	}
}

func TestGetLastHeartbeat_success(t *testing.T) {
	expectedHeartbeat := map[string]interface{}{
		"timestamp": "2026-07-17T10:00:00Z",
		"nodeId":    "node-1",
		"status":    "RUNNING",
	}

	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != "GET" {
			t.Errorf("expected GET, got %s", r.Method)
		}
		if r.URL.Path != "/api/control/workflows/workflow-123/nodes/node-1/heartbeat" {
			t.Errorf("expected /api/control/workflows/workflow-123/nodes/node-1/heartbeat, got %s", r.URL.Path)
		}

		response := map[string]interface{}{
			"data": expectedHeartbeat,
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(response)
	}))
	defer server.Close()

	c := NewClient(server.URL)
	heartbeat, err := c.GetLastHeartbeat("workflow-123", "node-1")

	if err != nil {
		t.Errorf("unexpected error: %v", err)
	}
	if heartbeat["nodeId"] != "node-1" {
		t.Errorf("expected nodeId 'node-1', got %v", heartbeat["nodeId"])
	}
}

func TestGetLastHeartbeat_emptyIDs(t *testing.T) {
	c := NewClient("http://localhost:8080")

	_, err := c.GetLastHeartbeat("", "node-1")
	if err == nil {
		t.Error("expected error for empty workflowID, got nil")
	}

	_, err = c.GetLastHeartbeat("workflow-123", "")
	if err == nil {
		t.Error("expected error for empty nodeID, got nil")
	}
}

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
	result, err := c.SendCommand("workflow-123", "node-1", commandPayload)

	if err != nil {
		t.Errorf("unexpected error: %v", err)
	}
	if result["status"] != "success" {
		t.Errorf("expected status 'success', got %v", result["status"])
	}
}

func TestSendCommand_emptyPayload(t *testing.T) {
	c := NewClient("http://localhost:8080")
	_, err := c.SendCommand("workflow-123", "node-1", []byte{})

	if err == nil {
		t.Fatal("expected error for empty payload, got nil")
	}
	if !strings.Contains(err.Error(), "empty") {
		t.Errorf("expected empty error message, got: %v", err)
	}
}

func TestSendCommand_emptyWorkflowID(t *testing.T) {
	c := NewClient("http://localhost:8080")
	_, err := c.SendCommand("", "node-1", []byte(`{"action":"pause"}`))

	if err == nil {
		t.Fatal("expected error for empty workflowID, got nil")
	}
	if !strings.Contains(err.Error(), "workflowID") {
		t.Errorf("expected workflowID error message, got: %v", err)
	}
}

func TestSendCommand_emptyNodeID(t *testing.T) {
	c := NewClient("http://localhost:8080")
	_, err := c.SendCommand("workflow-123", "", []byte(`{"action":"pause"}`))

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
	_, err := c.SendCommand("workflow-123", "nonexistent", []byte(`{"action":"pause"}`))

	if err == nil {
		t.Fatal("expected error, got nil")
	}
	if !strings.Contains(err.Error(), "API error") || !strings.Contains(err.Error(), "404") {
		t.Errorf("expected 404 API error, got: %v", err)
	}
}

// ===== Malformed JSON Tests (Complete Coverage) =====

func TestGetActiveNodesInWorkflow_malformedJSON(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{invalid json`))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	_, err := c.GetActiveNodesInWorkflow("workflow-123")

	if err == nil {
		t.Fatal("expected error for malformed JSON, got nil")
	}
	if !strings.Contains(err.Error(), "failed to unmarshal response") {
		t.Errorf("expected unmarshal error, got: %v", err)
	}
}

func TestGetAllActiveNodes_malformedJSON(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{invalid json`))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	_, err := c.GetAllActiveNodes()

	if err == nil {
		t.Fatal("expected error for malformed JSON, got nil")
	}
	if !strings.Contains(err.Error(), "failed to unmarshal response") {
		t.Errorf("expected unmarshal error, got: %v", err)
	}
}

func TestGetLastHeartbeat_malformedJSON(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{invalid json`))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	_, err := c.GetLastHeartbeat("workflow-123", "node-1")

	if err == nil {
		t.Fatal("expected error for malformed JSON, got nil")
	}
	if !strings.Contains(err.Error(), "failed to unmarshal response") {
		t.Errorf("expected unmarshal error, got: %v", err)
	}
}

func TestSendCommand_malformedJSON(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{invalid json`))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	_, err := c.SendCommand("workflow-123", "node-1", []byte(`{"action":"pause"}`))

	if err == nil {
		t.Fatal("expected error for malformed JSON, got nil")
	}
	if !strings.Contains(err.Error(), "failed to unmarshal response") {
		t.Errorf("expected unmarshal error, got: %v", err)
	}
}

// ===== RequestFactory Failure Tests (Complete Coverage) =====

func TestGetActiveNodesInWorkflow_requestFactoryFails_returnsError(t *testing.T) {
	mockFactory := &MockRequestFactory{
		Err: errors.New("failed to create request"),
	}

	c := &Client{
		BaseURL:        "http://localhost:8080",
		HTTPClient:     &http.Client{},
		RequestFactory: mockFactory,
		httpDoer:       &http.Client{},
	}

	_, err := c.GetActiveNodesInWorkflow("workflow-123")
	if err == nil {
		t.Fatal("expected error when factory fails, got nil")
	}
	if !strings.Contains(err.Error(), "failed to create request") {
		t.Errorf("expected factory error in GetActiveNodesInWorkflow, got: %v", err)
	}
}

func TestGetAllActiveNodes_requestFactoryFails_returnsError(t *testing.T) {
	mockFactory := &MockRequestFactory{
		Err: errors.New("failed to create request"),
	}

	c := &Client{
		BaseURL:        "http://localhost:8080",
		HTTPClient:     &http.Client{},
		RequestFactory: mockFactory,
		httpDoer:       &http.Client{},
	}

	_, err := c.GetAllActiveNodes()
	if err == nil {
		t.Fatal("expected error when factory fails, got nil")
	}
	if !strings.Contains(err.Error(), "failed to create request") {
		t.Errorf("expected factory error in GetAllActiveNodes, got: %v", err)
	}
}

func TestGetLastHeartbeat_requestFactoryFails_returnsError(t *testing.T) {
	mockFactory := &MockRequestFactory{
		Err: errors.New("failed to create request"),
	}

	c := &Client{
		BaseURL:        "http://localhost:8080",
		HTTPClient:     &http.Client{},
		RequestFactory: mockFactory,
		httpDoer:       &http.Client{},
	}

	_, err := c.GetLastHeartbeat("workflow-123", "node-1")
	if err == nil {
		t.Fatal("expected error when factory fails, got nil")
	}
	if !strings.Contains(err.Error(), "failed to create request") {
		t.Errorf("expected factory error in GetLastHeartbeat, got: %v", err)
	}
}

func TestSendCommand_requestFactoryFails_returnsError(t *testing.T) {
	mockFactory := &MockRequestFactory{
		Err: errors.New("failed to create request"),
	}

	c := &Client{
		BaseURL:        "http://localhost:8080",
		HTTPClient:     &http.Client{},
		RequestFactory: mockFactory,
		httpDoer:       &http.Client{},
	}

	_, err := c.SendCommand("workflow-123", "node-1", []byte(`{"action":"pause"}`))
	if err == nil {
		t.Fatal("expected error when factory fails, got nil")
	}
	if !strings.Contains(err.Error(), "failed to create request") {
		t.Errorf("expected factory error in SendCommand, got: %v", err)
	}
}

// ===== HTTPDoer Failure Tests (Complete Coverage) =====

func TestGetActiveNodesInWorkflow_httpDoerFails_returnsError(t *testing.T) {
	mockDoer := &MockHTTPDoer{
		Err: errors.New("connection refused"),
	}

	c := &Client{
		BaseURL:        "http://localhost:8080",
		HTTPClient:     &http.Client{},
		RequestFactory: &DefaultRequestFactory{},
		httpDoer:       mockDoer,
	}

	_, err := c.GetActiveNodesInWorkflow("workflow-123")
	if err == nil {
		t.Fatal("expected error when HTTP doer fails, got nil")
	}
	if !strings.Contains(err.Error(), "request failed") {
		t.Errorf("expected doer error in GetActiveNodesInWorkflow, got: %v", err)
	}
}

func TestGetAllActiveNodes_httpDoerFails_returnsError(t *testing.T) {
	mockDoer := &MockHTTPDoer{
		Err: errors.New("connection timeout"),
	}

	c := &Client{
		BaseURL:        "http://localhost:8080",
		HTTPClient:     &http.Client{},
		RequestFactory: &DefaultRequestFactory{},
		httpDoer:       mockDoer,
	}

	_, err := c.GetAllActiveNodes()
	if err == nil {
		t.Fatal("expected error when HTTP doer fails, got nil")
	}
	if !strings.Contains(err.Error(), "request failed") {
		t.Errorf("expected doer error in GetAllActiveNodes, got: %v", err)
	}
}

func TestGetLastHeartbeat_httpDoerFails_returnsError(t *testing.T) {
	mockDoer := &MockHTTPDoer{
		Err: errors.New("network error"),
	}

	c := &Client{
		BaseURL:        "http://localhost:8080",
		HTTPClient:     &http.Client{},
		RequestFactory: &DefaultRequestFactory{},
		httpDoer:       mockDoer,
	}

	_, err := c.GetLastHeartbeat("workflow-123", "node-1")
	if err == nil {
		t.Fatal("expected error when HTTP doer fails, got nil")
	}
	if !strings.Contains(err.Error(), "request failed") {
		t.Errorf("expected doer error in GetLastHeartbeat, got: %v", err)
	}
}

func TestSendCommand_httpDoerFails_returnsError(t *testing.T) {
	mockDoer := &MockHTTPDoer{
		Err: errors.New("connection error"),
	}

	c := &Client{
		BaseURL:        "http://localhost:8080",
		HTTPClient:     &http.Client{},
		RequestFactory: &DefaultRequestFactory{},
		httpDoer:       mockDoer,
	}

	_, err := c.SendCommand("workflow-123", "node-1", []byte(`{"action":"pause"}`))
	if err == nil {
		t.Fatal("expected error when HTTP doer fails, got nil")
	}
	if !strings.Contains(err.Error(), "request failed") {
		t.Errorf("expected doer error in SendCommand, got: %v", err)
	}
}
