// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package plugin

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

func TestGetCmd_requiresPluginType(t *testing.T) {
	c := client.NewClient("http://localhost:8080")
	cmd := GetCmd(c)

	// Cobra's ExactArgs(1) should reject with no args
	if cmd.Args == nil {
		t.Error("expected Args to be set")
	}
}

func TestGetCmd_executesSuccessfully_tableFormat(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != "GET" {
			t.Errorf("expected GET, got %s", r.Method)
		}
		if r.URL.Path != "/api/plugins/test-plugin" {
			t.Errorf("expected /api/plugins/test-plugin, got %s", r.URL.Path)
		}

		w.Header().Set("Content-Type", "application/json")
		response := map[string]interface{}{
			"data": map[string]interface{}{
				"type":         "test-plugin",
				"category":     "PROCESSOR",
				"description":  "Test plugin",
				"usagePattern": "test-pattern",
				"uiDesign": map[string]interface{}{
					"html":   "<div>Test</div>",
					"width":  100,
					"height": 50,
				},
				"outputPorts": []string{"output1"},
			},
		}
		_ = json.NewEncoder(w).Encode(response)
	}))
	defer server.Close()

	c := client.NewClient(server.URL)
	cmd := GetCmd(c)

	origFormat := commands.GetOutputFormat()
	commands.SetTestOutputFormat("table")
	defer commands.SetTestOutputFormat(origFormat)

	r, w, _ := os.Pipe()
	defer r.Close()
	oldStdout := os.Stdout
	os.Stdout = w

	err := cmd.RunE(cmd, []string{"test-plugin"})

	w.Close()
	os.Stdout = oldStdout

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	var buf bytes.Buffer
	_, _ = io.Copy(&buf, r)
	output := buf.String()

	if !strings.Contains(output, "test-plugin") {
		t.Errorf("expected 'test-plugin' in output, got: %s", output)
	}
	if !strings.Contains(output, "PROCESSOR") {
		t.Errorf("expected 'PROCESSOR' in output, got: %s", output)
	}
	if !strings.Contains(output, "Test plugin") {
		t.Errorf("expected description in output, got: %s", output)
	}
}

func TestGetCmd_executesSuccessfully_jsonFormat(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		response := map[string]interface{}{
			"data": map[string]interface{}{
				"type":         "test-plugin",
				"category":     "PROCESSOR",
				"description":  "Test plugin",
				"usagePattern": "test-pattern",
				"uiDesign": map[string]interface{}{
					"html":   "<div>Test</div>",
					"width":  100,
					"height": 50,
				},
				"outputPorts": []string{"output1"},
			},
		}
		_ = json.NewEncoder(w).Encode(response)
	}))
	defer server.Close()

	c := client.NewClient(server.URL)
	cmd := GetCmd(c)

	origFormat := commands.GetOutputFormat()
	commands.SetTestOutputFormat("json")
	defer commands.SetTestOutputFormat(origFormat)

	r, w, _ := os.Pipe()
	defer r.Close()
	oldStdout := os.Stdout
	os.Stdout = w

	err := cmd.RunE(cmd, []string{"test-plugin"})

	w.Close()
	os.Stdout = oldStdout

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	var buf bytes.Buffer
	_, _ = io.Copy(&buf, r)
	output := buf.String()

	if !strings.Contains(output, "test-plugin") {
		t.Errorf("expected 'test-plugin' in JSON output, got: %s", output)
	}
}

func TestGetCmd_handlesNotFound(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNotFound)
		_, _ = w.Write([]byte("Plugin not found"))
	}))
	defer server.Close()

	c := client.NewClient(server.URL)
	cmd := GetCmd(c)

	err := cmd.RunE(cmd, []string{"nonexistent"})

	if err == nil {
		t.Error("expected error, got nil")
	}
	if !strings.Contains(err.Error(), "failed to get plugin details") {
		t.Errorf("expected wrapped error, got: %v", err)
	}
}
