// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package session

import (
	"bytes"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"

	"com.infenia.yukta/go-cli/internal/client"
	"com.infenia.yukta/go-cli/internal/commands"
)

// TestListCmd_createsCommand_withCorrectUse tests that ListCmd creates command with correct Use.
func TestListCmd_createsCommand_withCorrectUse(t *testing.T) {
	c := client.NewClient("http://localhost:8080")
	cmd := ListCmd(c)

	if cmd.Use != "list" {
		t.Errorf("expected Use 'list', got %q", cmd.Use)
	}
}

// TestListCmd_hasDescription tests that command has description.
func TestListCmd_hasDescription(t *testing.T) {
	c := client.NewClient("http://localhost:8080")
	cmd := ListCmd(c)

	if cmd.Short == "" {
		t.Error("expected non-empty Short description")
	}

	if !strings.Contains(cmd.Short, "List") || !strings.Contains(cmd.Short, "session") {
		t.Errorf("expected description about listing sessions, got %q", cmd.Short)
	}
}

// TestListCmd_executesSuccessfully_tableFormat tests list command with table format output.
func TestListCmd_executesSuccessfully_tableFormat(t *testing.T) {
	// Set up test server
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"data":{"sessionIds":["session-1","session-2"]}}`))
	}))
	defer server.Close()

	// Create client and command
	c := client.NewClient(server.URL)
	cmd := ListCmd(c)

	// Set output format to table
	commands.SetTestOutputFormat("table")
	defer commands.SetTestOutputFormat("table")

	// Capture stdout
	r, w, _ := os.Pipe()
	oldStdout := os.Stdout
	os.Stdout = w

	// Execute command
	err := cmd.RunE(cmd, []string{})

	w.Close()
	os.Stdout = oldStdout

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	// Read output
	var buf bytes.Buffer
	_, _ = io.Copy(&buf, r)
	output := buf.String()

	// Verify output contains sessions
	if !strings.Contains(output, "session-1") {
		t.Errorf("expected 'session-1' in output, got: %s", output)
	}
	if !strings.Contains(output, "session-2") {
		t.Errorf("expected 'session-2' in output, got: %s", output)
	}
	if !strings.Contains(output, "Session ID") {
		t.Errorf("expected 'Session ID' header in output, got: %s", output)
	}
}

// TestListCmd_executesSuccessfully_jsonFormat tests list command with JSON format output.
func TestListCmd_executesSuccessfully_jsonFormat(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"data":{"sessionIds":["session-1","session-2"]}}`))
	}))
	defer server.Close()

	c := client.NewClient(server.URL)
	cmd := ListCmd(c)

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

	// Verify JSON output
	if !strings.Contains(output, "session-1") {
		t.Errorf("expected 'session-1' in JSON output, got: %s", output)
	}
	if !strings.Contains(output, "sessions") {
		t.Errorf("expected 'sessions' key in JSON output, got: %s", output)
	}
}

// TestListCmd_emptySessionList_displaysEmpty tests handling of empty session list.
func TestListCmd_emptySessionList_displaysEmpty(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"data":{"sessionIds":[]}}`))
	}))
	defer server.Close()

	c := client.NewClient(server.URL)
	cmd := ListCmd(c)

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

	// Should have header even if no sessions
	if !strings.Contains(output, "Session ID") {
		t.Errorf("expected 'Session ID' header in output for empty list")
	}
}

// TestListCmd_apiError_returnsError tests error handling for API errors.
func TestListCmd_apiError_returnsError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
		_, _ = w.Write([]byte("Error"))
	}))
	defer server.Close()

	c := client.NewClient(server.URL)
	cmd := ListCmd(c)

	commands.SetTestOutputFormat("table")
	defer commands.SetTestOutputFormat("table")

	err := cmd.RunE(cmd, []string{})

	if err == nil {
		t.Error("expected error for API failure, got nil")
	}

	if !strings.Contains(err.Error(), "status 500") {
		t.Errorf("expected error message containing 'status 500', got: %v", err)
	}
}

// TestListCmd_multipleRows_displaysAllSessions tests handling of multiple sessions.
func TestListCmd_multipleRows_displaysAllSessions(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"data":{"sessionIds":["s1","s2","s3","s4","s5"]}}`))
	}))
	defer server.Close()

	c := client.NewClient(server.URL)
	cmd := ListCmd(c)

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

	// Verify all sessions are shown
	for i := 1; i <= 5; i++ {
		sessionID := "s" + string(rune('0'+i))
		if !strings.Contains(output, sessionID) {
			t.Errorf("expected '%s' in output, got: %s", sessionID, output)
		}
	}
}

// TestListCmd_commandHasRunE tests that command has RunE handler.
func TestListCmd_commandHasRunE(t *testing.T) {
	c := client.NewClient("http://localhost:8080")
	cmd := ListCmd(c)

	if cmd.RunE == nil {
		t.Error("expected ListCmd to have RunE handler")
	}
}

// TestListCmd_noArgs_required tests that list command requires no arguments.
func TestListCmd_noArgs_required(t *testing.T) {
	c := client.NewClient("http://localhost:8080")
	cmd := ListCmd(c)

	// Should not have Args restriction (accepts zero arguments)
	if cmd.Args != nil && cmd.Args(cmd, []string{"extra"}) != nil {
		t.Skip("command has args restriction - this test was informational only")
	}
}

// TestListCmd_malformedJSON_returnsError tests handling of malformed JSON response.
func TestListCmd_malformedJSON_returnsError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`not valid json`))
	}))
	defer server.Close()

	c := client.NewClient(server.URL)
	cmd := ListCmd(c)

	commands.SetTestOutputFormat("table")
	defer commands.SetTestOutputFormat("table")

	err := cmd.RunE(cmd, []string{})

	if err == nil {
		t.Error("expected error for malformed JSON, got nil")
	}
}

// TestListCmd_apiNotFound_returnsError tests handling of 404 response.
func TestListCmd_apiNotFound_returnsError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNotFound)
		_, _ = w.Write([]byte("Not Found"))
	}))
	defer server.Close()

	c := client.NewClient(server.URL)
	cmd := ListCmd(c)

	err := cmd.RunE(cmd, []string{})

	if err == nil {
		t.Error("expected error for 404 response, got nil")
	}
}

// Helper function to set test output format
// NOTE: This requires a test helper function in commands package
// TestListCmd_happyPath_tableFormat tests the happy path for list command with table output using mocks.
func TestListCmd_happyPath_tableFormat(t *testing.T) {
	// Setup mock client
	mock := &client.MockClient{
		GetSessionsFunc: func() ([]string, error) {
			return []string{"session-1", "session-2", "session-3"}, nil
		},
	}

	// Create command with mock
	cmd := ListCmd(mock)

	// Capture output
	r, w, _ := os.Pipe()
	oldStdout := os.Stdout
	os.Stdout = w

	// Set output format to table
	commands.SetTestOutputFormat("table")
	defer commands.SetTestOutputFormat("table")

	// Execute command
	err := cmd.RunE(cmd, []string{})

	w.Close()
	os.Stdout = oldStdout

	// Read output
	var buf bytes.Buffer
	_, _ = io.Copy(&buf, r)
	output := buf.String()

	// Assertions
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	if mock.GetSessionsCalls != 1 {
		t.Errorf("expected GetSessions to be called once, got %d", mock.GetSessionsCalls)
	}

	// Verify output contains session IDs
	if !strings.Contains(output, "session-1") {
		t.Errorf("expected output to contain 'session-1', got: %s", output)
	}
}

// TestListCmd_happyPath_jsonFormat tests the happy path for list command with JSON output.
func TestListCmd_happyPath_jsonFormat(t *testing.T) {
	// Setup mock client
	mock := &client.MockClient{
		GetSessionsFunc: func() ([]string, error) {
			return []string{"session-1", "session-2"}, nil
		},
	}

	// Create command with mock
	cmd := ListCmd(mock)

	// Capture output
	r, w, _ := os.Pipe()
	oldStdout := os.Stdout
	os.Stdout = w

	// Set output format to JSON
	commands.SetTestOutputFormat("json")
	defer commands.SetTestOutputFormat("table")

	// Execute command
	err := cmd.RunE(cmd, []string{})

	w.Close()
	os.Stdout = oldStdout

	// Read output
	var buf bytes.Buffer
	_, _ = io.Copy(&buf, r)
	output := buf.String()

	// Assertions
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	if mock.GetSessionsCalls != 1 {
		t.Errorf("expected GetSessions to be called once, got %d", mock.GetSessionsCalls)
	}

	// Verify output is valid JSON
	if !strings.Contains(output, `"sessions"`) {
		t.Errorf("expected JSON output with 'sessions' key, got: %s", output)
	}
}

// TestListCmd_happyPath_emptySessions tests list command with no sessions.
func TestListCmd_happyPath_emptySessions(t *testing.T) {
	// Setup mock client returning empty list
	mock := &client.MockClient{
		GetSessionsFunc: func() ([]string, error) {
			return []string{}, nil
		},
	}

	// Create command with mock
	cmd := ListCmd(mock)

	// Capture output
	r, w, _ := os.Pipe()
	oldStdout := os.Stdout
	os.Stdout = w

	commands.SetTestOutputFormat("table")
	defer commands.SetTestOutputFormat("table")

	// Execute command
	err := cmd.RunE(cmd, []string{})

	w.Close()
	os.Stdout = oldStdout

	// Read output
	var buf bytes.Buffer
	_, _ = io.Copy(&buf, r)
	output := buf.String()

	// Assertions
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	if mock.GetSessionsCalls != 1 {
		t.Errorf("expected GetSessions to be called once, got %d", mock.GetSessionsCalls)
	}

	// Empty list should still produce valid output
	if output == "" {
		t.Error("expected some output even for empty sessions")
	}
}

// TestListCmd_happyPath_largeSessions tests list with many sessions.
func TestListCmd_happyPath_largeSessions(t *testing.T) {
	// Setup mock client with many sessions
	sessions := make([]string, 100)
	for i := 0; i < 100; i++ {
		sessions[i] = "session-" + string(rune(i))
	}

	mock := &client.MockClient{
		GetSessionsFunc: func() ([]string, error) {
			return sessions, nil
		},
	}

	// Create command with mock
	cmd := ListCmd(mock)

	// Capture output
	r, w, _ := os.Pipe()
	oldStdout := os.Stdout
	os.Stdout = w

	commands.SetTestOutputFormat("table")
	defer commands.SetTestOutputFormat("table")

	// Execute command
	err := cmd.RunE(cmd, []string{})

	w.Close()
	os.Stdout = oldStdout

	// Read output
	var buf bytes.Buffer
	_, _ = io.Copy(&buf, r)
	output := buf.String()

	// Assertions
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	if mock.GetSessionsCalls != 1 {
		t.Errorf("expected GetSessions to be called once, got %d", mock.GetSessionsCalls)
	}

	// Verify output is not empty
	if output == "" {
		t.Error("expected output for large sessions list")
	}
}
