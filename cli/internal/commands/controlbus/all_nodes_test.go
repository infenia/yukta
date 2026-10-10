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

func TestAllNodesCmd_createsCommand(t *testing.T) {
	c := client.NewClient("http://localhost:8080")
	cmd := AllNodesCmd(c)

	if cmd.Use != "all-nodes" {
		t.Errorf("expected Use 'all-nodes', got %q", cmd.Use)
	}
}

func TestAllNodesCmd_executesSuccessfully_tableFormat(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != "GET" {
			t.Errorf("expected GET, got %s", r.Method)
		}
		if r.URL.Path != "/api/control/nodes" {
			t.Errorf("expected /api/control/nodes, got %s", r.URL.Path)
		}

		w.Header().Set("Content-Type", "application/json")
		response := map[string]interface{}{
			"data": []string{"global-node-1", "global-node-2"},
		}
		_ = json.NewEncoder(w).Encode(response)
	}))
	defer server.Close()

	c := client.NewClient(server.URL)
	cmd := AllNodesCmd(c)

	commands.SetTestOutputFormat("table")
	defer commands.SetTestOutputFormat("table")

	r, w, _ := os.Pipe()
	oldStdout := os.Stdout
	os.Stdout = w

	err := cmd.RunE(cmd, []string{})

	w.Close()
	os.Stdout = oldStdout

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	var buf bytes.Buffer
	_, _ = io.Copy(&buf, r)
	output := buf.String()

	if !strings.Contains(output, "global-node-1") {
		t.Errorf("expected 'global-node-1' in output, got: %s", output)
	}
}

func TestAllNodesCmd_executesSuccessfully_jsonFormat(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		response := map[string]interface{}{
			"data": []string{"global-node-1"},
		}
		_ = json.NewEncoder(w).Encode(response)
	}))
	defer server.Close()

	c := client.NewClient(server.URL)
	cmd := AllNodesCmd(c)

	commands.SetTestOutputFormat("json")
	defer commands.SetTestOutputFormat("table")

	r, w, _ := os.Pipe()
	oldStdout := os.Stdout
	os.Stdout = w

	err := cmd.RunE(cmd, []string{})

	w.Close()
	os.Stdout = oldStdout

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	var buf bytes.Buffer
	_, _ = io.Copy(&buf, r)
	output := buf.String()

	if !strings.Contains(output, "global-node-1") {
		t.Errorf("expected 'global-node-1' in JSON output, got: %s", output)
	}
}

func TestAllNodesCmd_handlesApiError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
		_, _ = w.Write([]byte("Internal server error"))
	}))
	defer server.Close()

	c := client.NewClient(server.URL)
	cmd := AllNodesCmd(c)

	err := cmd.RunE(cmd, []string{})

	if err == nil {
		t.Error("expected error for server error, got nil")
	}
}

func TestAllNodesCmd_emptyNodes_tableFormat(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		response := map[string]interface{}{
			"data": []string{},
		}
		_ = json.NewEncoder(w).Encode(response)
	}))
	defer server.Close()

	c := client.NewClient(server.URL)
	cmd := AllNodesCmd(c)

	commands.SetTestOutputFormat("table")
	defer commands.SetTestOutputFormat("table")

	r, w, _ := os.Pipe()
	oldStdout := os.Stdout
	os.Stdout = w

	err := cmd.RunE(cmd, []string{})

	w.Close()
	os.Stdout = oldStdout

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	var buf bytes.Buffer
	_, _ = io.Copy(&buf, r)
}

func TestAllNodesCmd_singleNode(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		response := map[string]interface{}{
			"data": []string{"single-node"},
		}
		_ = json.NewEncoder(w).Encode(response)
	}))
	defer server.Close()

	c := client.NewClient(server.URL)
	cmd := AllNodesCmd(c)

	commands.SetTestOutputFormat("table")
	defer commands.SetTestOutputFormat("table")

	r, w, _ := os.Pipe()
	oldStdout := os.Stdout
	os.Stdout = w

	err := cmd.RunE(cmd, []string{})

	w.Close()
	os.Stdout = oldStdout

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	var buf bytes.Buffer
	_, _ = io.Copy(&buf, r)
	output := buf.String()

	if !strings.Contains(output, "single-node") {
		t.Errorf("expected 'single-node' in output, got: %s", output)
	}
}

func TestAllNodesCmd_formatCheck(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		response := map[string]interface{}{
			"data": []string{"node1", "node2", "node3"},
		}
		_ = json.NewEncoder(w).Encode(response)
	}))
	defer server.Close()

	c := client.NewClient(server.URL)
	cmd := AllNodesCmd(c)

	for _, format := range []string{"table", "json", "table"} {
		commands.SetTestOutputFormat(format)
		r, w, _ := os.Pipe()
		oldStdout := os.Stdout
		os.Stdout = w

		err := cmd.RunE(cmd, []string{})

		w.Close()
		os.Stdout = oldStdout

		if err != nil {
			t.Fatalf("unexpected error with format %s: %v", format, err)
		}

		var buf bytes.Buffer
		_, _ = io.Copy(&buf, r)
		output := buf.String()

		if !strings.Contains(output, "node1") {
			t.Errorf("expected nodes in output with format %s, got: %s", format, output)
		}
	}

	commands.SetTestOutputFormat("table")
}
