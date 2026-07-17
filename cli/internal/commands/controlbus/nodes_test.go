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

func TestNodesCmd_createsCommand(t *testing.T) {
	c := client.NewClient("http://localhost:8080")
	cmd := NodesCmd(c)

	if !strings.HasPrefix(cmd.Use, "nodes") {
		t.Errorf("expected Use starting with 'nodes', got %q", cmd.Use)
	}
}

func TestNodesCmd_executesSuccessfully_tableFormat(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != "GET" {
			t.Errorf("expected GET, got %s", r.Method)
		}
		if r.URL.Path != "/api/control/workflows/workflow-123/nodes" {
			t.Errorf("expected /api/control/workflows/workflow-123/nodes, got %s", r.URL.Path)
		}

		w.Header().Set("Content-Type", "application/json")
		response := map[string]interface{}{
			"data": []string{"node-1", "node-2"},
		}
		_ = json.NewEncoder(w).Encode(response)
	}))
	defer server.Close()

	c := client.NewClient(server.URL)
	cmd := NodesCmd(c)

	origFormat := commands.GetOutputFormat()
	commands.SetTestOutputFormat("table")
	defer commands.SetTestOutputFormat(origFormat)

	r, w, _ := os.Pipe()
	defer r.Close()
	oldStdout := os.Stdout
	os.Stdout = w

	err := cmd.RunE(cmd, []string{"workflow-123"})

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
	if !strings.Contains(output, "node-2") {
		t.Errorf("expected 'node-2' in output, got: %s", output)
	}
}

func TestNodesCmd_executesSuccessfully_jsonFormat(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		response := map[string]interface{}{
			"data": []string{"node-1"},
		}
		_ = json.NewEncoder(w).Encode(response)
	}))
	defer server.Close()

	c := client.NewClient(server.URL)
	cmd := NodesCmd(c)

	origFormat := commands.GetOutputFormat()
	commands.SetTestOutputFormat("json")
	defer commands.SetTestOutputFormat(origFormat)

	r, w, _ := os.Pipe()
	defer r.Close()
	oldStdout := os.Stdout
	os.Stdout = w

	err := cmd.RunE(cmd, []string{"workflow-123"})

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
	if !strings.Contains(output, "workflow-123") {
		t.Errorf("expected 'workflow-123' in JSON output, got: %s", output)
	}
}

func TestNodesCmd_handlesApiError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNotFound)
		_, _ = w.Write([]byte("Workflow not found"))
	}))
	defer server.Close()

	c := client.NewClient(server.URL)
	cmd := NodesCmd(c)

	err := cmd.RunE(cmd, []string{"nonexistent"})

	if err == nil {
		t.Error("expected error, got nil")
	}
	if !strings.Contains(err.Error(), "failed to get active nodes") {
		t.Errorf("expected wrapped error, got: %v", err)
	}
}
