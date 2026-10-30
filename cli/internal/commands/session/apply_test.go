// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package session

import (
	"bytes"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"com.infenia.yukta/go-cli/internal/client"
	"com.infenia.yukta/go-cli/internal/commands"
)

// TestReadConfigInputFromFile tests reading configuration from a file.
// TestReadConfigInputFromInlineJSON tests reading configuration from inline JSON.
// TestReadConfigInputFromNonexistentFile tests handling of nonexistent file paths.
// TestReadConfigInputFromDirectory tests handling when input is a directory path.
// TestApplyCmd_createsCommand_withCorrectUse tests that ApplyCmd creates command with correct Use.
func TestApplyCmd_createsCommand_withCorrectUse(t *testing.T) {
	c := client.NewClient("http://localhost:9858")
	cmd := ApplyCmd(c)

	if !strings.Contains(cmd.Use, "apply") {
		t.Errorf("expected Use to contain 'apply', got %q", cmd.Use)
	}
}

// TestApplyCmd_hasDescription tests that command has description.
func TestApplyCmd_hasDescription(t *testing.T) {
	c := client.NewClient("http://localhost:9858")
	cmd := ApplyCmd(c)

	if cmd.Short == "" {
		t.Error("expected non-empty Short description")
	}

	if !strings.Contains(cmd.Short, "Apply") {
		t.Errorf("expected 'Apply' in short description, got %q", cmd.Short)
	}
}

// TestApplyCmd_commandHasRunE tests that command has RunE handler.
func TestApplyCmd_commandHasRunE(t *testing.T) {
	c := client.NewClient("http://localhost:9858")
	cmd := ApplyCmd(c)

	if cmd.RunE == nil {
		t.Error("expected ApplyCmd to have RunE handler")
	}
}

// TestApplyCmd_invalidJSON_returnsError tests handling of invalid JSON input.
func TestApplyCmd_invalidJSON_returnsError(t *testing.T) {
	c := client.NewClient("http://localhost:9858")
	cmd := ApplyCmd(c)

	err := cmd.RunE(cmd, []string{`{invalid json}`})

	if err == nil {
		t.Error("expected error for invalid JSON, got nil")
	}

	if !strings.Contains(err.Error(), "invalid JSON") {
		t.Errorf("expected 'invalid JSON' in error, got: %v", err)
	}
}

// TestReadConfigInput_largeJSON tests handling of large JSON strings.
// TestReadConfigInput_jsonWithSpecialChars tests handling of JSON with special characters.
// TestApplyCmd_successPath_tableOutput tests the success path with table format output.
func TestApplyCmd_successPath_tableOutput(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/sessions" {
			t.Errorf("expected path /api/sessions, got %s", r.URL.Path)
		}
		if r.Method != "POST" {
			t.Errorf("expected POST method, got %s", r.Method)
		}
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"status":"success"}`))
	}))
	defer server.Close()

	c := client.NewClient(server.URL)
	cmd := ApplyCmd(c)

	// Capture stdout
	r, w, _ := os.Pipe()
	oldStdout := os.Stdout
	os.Stdout = w

	err := cmd.RunE(cmd, []string{`{"sessionId":"test-session"}`})

	w.Close()
	os.Stdout = oldStdout

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	var buf bytes.Buffer
	_, _ = io.Copy(&buf, r)
	output := buf.String()

	if !strings.Contains(output, "Session configuration applied successfully") {
		t.Errorf("expected 'Session configuration applied successfully' in output, got: %s", output)
	}
}

// TestApplyCmd_successPath_jsonOutput tests the success path with JSON format output.
func TestApplyCmd_successPath_jsonOutput(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"status":"success"}`))
	}))
	defer server.Close()

	c := client.NewClient(server.URL)
	cmd := ApplyCmd(c)

	commands.SetTestOutputFormat("json")
	defer commands.SetTestOutputFormat("table")

	// Capture stdout
	r, w, _ := os.Pipe()
	oldStdout := os.Stdout
	os.Stdout = w

	err := cmd.RunE(cmd, []string{`{"sessionId":"test-session"}`})

	w.Close()
	os.Stdout = oldStdout

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	var buf bytes.Buffer
	_, _ = io.Copy(&buf, r)
	output := buf.String()

	if !strings.Contains(output, "status") {
		t.Errorf("expected 'status' in JSON output, got: %s", output)
	}

	if !strings.Contains(output, "Configuration applied successfully") {
		t.Errorf("expected 'Configuration applied successfully' in JSON output, got: %s", output)
	}
}

// TestApplyCmd_apiFailure_returnsError tests error handling when API returns error status.
func TestApplyCmd_apiFailure_returnsError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusBadRequest)
		_, _ = w.Write([]byte("Invalid configuration"))
	}))
	defer server.Close()

	c := client.NewClient(server.URL)
	cmd := ApplyCmd(c)

	commands.SetTestOutputFormat("table")
	defer commands.SetTestOutputFormat("table")

	err := cmd.RunE(cmd, []string{`{"sessionId":"test-session"}`})

	if err == nil {
		t.Error("expected error for API failure, got nil")
	}

	if !strings.Contains(err.Error(), "failed to apply configuration") {
		t.Errorf("expected 'failed to apply configuration' in error, got: %v", err)
	}
}

// TestApplyCmd_readConfigInput_error tests error handling when readConfigInput fails.
func TestApplyCmd_readConfigInput_error(t *testing.T) {
	// Skip on Windows or if running as root (can't test permission denied)
	if os.Geteuid() == 0 {
		t.Skip("skipping test when running as root")
	}

	c := client.NewClient("http://localhost:9858")
	cmd := ApplyCmd(c)

	// Create a file with no read permissions to trigger os.ReadFile error
	tmpDir, err := os.MkdirTemp("", "test-dir-*")
	if err != nil {
		t.Fatalf("failed to create temp directory: %v", err)
	}
	defer os.RemoveAll(tmpDir)

	// Create a file with content
	testPath := filepath.Join(tmpDir, "restricted.json")
	if err := os.WriteFile(testPath, []byte(`{"test":"data"}`), 0644); err != nil {
		t.Fatalf("failed to write test file: %v", err)
	}

	// Remove read permission to trigger error in os.ReadFile
	if err := os.Chmod(testPath, 0000); err != nil {
		t.Fatalf("failed to chmod file: %v", err)
	}

	// Try to read this file using ApplyCmd
	err = cmd.RunE(cmd, []string{testPath})

	if err == nil {
		t.Error("expected error when reading file with no permissions, got nil")
	}

	if !strings.Contains(err.Error(), "failed to read configuration") {
		t.Errorf("expected 'failed to read configuration' in error, got: %v", err)
	}
}
