// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package controlbus

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"

	"com.infenia.yukta/go-cli/internal/client"
	"com.infenia.yukta/go-cli/internal/commands"
)

func TestHeartbeatCmd_createsCommand(t *testing.T) {
	c := client.NewClient("http://localhost:8080")
	cmd := HeartbeatCmd(c)

	if !strings.HasPrefix(cmd.Use, "heartbeat") {
		t.Errorf("expected Use starting with 'heartbeat', got %q", cmd.Use)
	}
}

func TestHeartbeatCmd_executesSuccessfully_tableFormat(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != "GET" {
			t.Errorf("expected GET, got %s", r.Method)
		}
		if r.URL.Path != "/api/control/workflows/workflow-123/nodes/node-1/heartbeat" {
			t.Errorf("expected /api/control/workflows/workflow-123/nodes/node-1/heartbeat, got %s", r.URL.Path)
		}

		w.Header().Set("Content-Type", "application/json")
		response := map[string]interface{}{
			"data": map[string]interface{}{
				"timestamp": "2026-07-17T10:00:00Z",
				"nodeId":    "node-1",
				"status":    "RUNNING",
			},
		}
		_ = json.NewEncoder(w).Encode(response)
	}))
	defer server.Close()

	c := client.NewClient(server.URL)
	cmd := HeartbeatCmd(c)

	commands.SetTestOutputFormat("table")
	defer commands.SetTestOutputFormat("table")

	r, w, _ := os.Pipe()
	oldStdout := os.Stdout
	os.Stdout = w

	err := cmd.RunE(cmd, []string{"workflow-123", "node-1"})

	w.Close()
	os.Stdout = oldStdout

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	var buf bytes.Buffer
	_, _ = io.Copy(&buf, r)
	output := buf.String()

	if !strings.Contains(output, "node-1") {
		t.Errorf("expected 'node-1' in output, got: %s", output)
	}
	if !strings.Contains(output, "RUNNING") {
		t.Errorf("expected 'RUNNING' in output, got: %s", output)
	}
}

func TestHeartbeatCmd_executesSuccessfully_jsonFormat(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		response := map[string]interface{}{
			"data": map[string]interface{}{
				"timestamp": "2026-07-17T10:00:00Z",
				"nodeId":    "node-1",
				"status":    "RUNNING",
			},
		}
		_ = json.NewEncoder(w).Encode(response)
	}))
	defer server.Close()

	c := client.NewClient(server.URL)
	cmd := HeartbeatCmd(c)

	commands.SetTestOutputFormat("json")
	defer commands.SetTestOutputFormat("table")

	r, w, _ := os.Pipe()
	oldStdout := os.Stdout
	os.Stdout = w

	err := cmd.RunE(cmd, []string{"workflow-123", "node-1"})

	w.Close()
	os.Stdout = oldStdout

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	var buf bytes.Buffer
	_, _ = io.Copy(&buf, r)
	output := buf.String()

	if !strings.Contains(output, "node-1") {
		t.Errorf("expected 'node-1' in JSON output, got: %s", output)
	}
}

func TestHeartbeatCmd_handlesApiError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNotFound)
		_, _ = w.Write([]byte("Node not found"))
	}))
	defer server.Close()

	c := client.NewClient(server.URL)
	cmd := HeartbeatCmd(c)

	err := cmd.RunE(cmd, []string{"workflow-123", "nonexistent"})

	if err == nil {
		t.Error("expected error, got nil")
	}
	if !strings.Contains(err.Error(), "failed to get heartbeat") {
		t.Errorf("expected wrapped error, got: %v", err)
	}
}
