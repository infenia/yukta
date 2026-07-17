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

func TestListCmd_createsCommand_withCorrectUse(t *testing.T) {
	c := client.NewClient("http://localhost:8080")
	cmd := ListCmd(c)

	if cmd.Use != "list" {
		t.Errorf("expected Use 'list', got %q", cmd.Use)
	}
}

func TestListCmd_rejectsStrayArgs(t *testing.T) {
	c := client.NewClient("http://localhost:8080")
	cmd := ListCmd(c)

	if cmd.Args == nil {
		t.Fatal("expected Args to be set")
	}
	if err := cmd.Args(cmd, []string{"unexpected"}); err == nil {
		t.Error("expected error for stray positional argument, got nil")
	}
}

func TestListCmd_executesSuccessfully_tableFormat(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != "GET" {
			t.Errorf("expected GET, got %s", r.Method)
		}
		if r.URL.Path != "/api/plugins" {
			t.Errorf("expected /api/plugins, got %s", r.URL.Path)
		}

		w.Header().Set("Content-Type", "application/json")
		response := map[string]interface{}{
			"data": []map[string]interface{}{
				{"type": "trigger-1", "category": "TRIGGER"},
				{"type": "processor-1", "category": "PROCESSOR"},
			},
		}
		_ = json.NewEncoder(w).Encode(response)
	}))
	defer server.Close()

	c := client.NewClient(server.URL)
	cmd := ListCmd(c)

	origFormat := commands.GetOutputFormat()
	commands.SetTestOutputFormat("table")
	defer commands.SetTestOutputFormat(origFormat)

	r, w, _ := os.Pipe()
	defer r.Close()
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

	if !strings.Contains(output, "trigger-1") {
		t.Errorf("expected 'trigger-1' in output, got: %s", output)
	}
	if !strings.Contains(output, "TRIGGER") {
		t.Errorf("expected 'TRIGGER' in output, got: %s", output)
	}
}

func TestListCmd_executesSuccessfully_jsonFormat(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		response := map[string]interface{}{
			"data": []map[string]interface{}{
				{"type": "trigger-1", "category": "TRIGGER"},
			},
		}
		_ = json.NewEncoder(w).Encode(response)
	}))
	defer server.Close()

	c := client.NewClient(server.URL)
	cmd := ListCmd(c)

	origFormat := commands.GetOutputFormat()
	commands.SetTestOutputFormat("json")
	defer commands.SetTestOutputFormat(origFormat)

	r, w, _ := os.Pipe()
	defer r.Close()
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

	if !strings.Contains(output, "trigger-1") {
		t.Errorf("expected 'trigger-1' in JSON output, got: %s", output)
	}
}

func TestListCmd_handlesApiError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
		_, _ = w.Write([]byte("Internal server error"))
	}))
	defer server.Close()

	c := client.NewClient(server.URL)
	cmd := ListCmd(c)

	err := cmd.RunE(cmd, []string{})

	if err == nil {
		t.Error("expected error, got nil")
	}
	if !strings.Contains(err.Error(), "API error") {
		t.Errorf("expected API error message, got: %v", err)
	}
}
