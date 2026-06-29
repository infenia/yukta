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

// TestGetCmd_createsCommand_withCorrectUse tests that GetCmd creates command with correct Use.
func TestGetCmd_createsCommand_withCorrectUse(t *testing.T) {
	c := client.NewClient("http://localhost:8080")
	cmd := GetCmd(c)

	if !strings.Contains(cmd.Use, "get") {
		t.Errorf("expected Use to contain 'get', got %q", cmd.Use)
	}

	if !strings.Contains(cmd.Use, "session-id") {
		t.Errorf("expected Use to contain 'session-id', got %q", cmd.Use)
	}
}

// TestGetCmd_hasDescription tests that command has description.
func TestGetCmd_hasDescription(t *testing.T) {
	c := client.NewClient("http://localhost:8080")
	cmd := GetCmd(c)

	if cmd.Short == "" {
		t.Error("expected non-empty Short description")
	}

	if !strings.Contains(cmd.Short, "Get") {
		t.Errorf("expected 'Get' in short description, got %q", cmd.Short)
	}
}

// TestGetCmd_requiresExactlyOneArg tests that command requires exactly one argument.
func TestGetCmd_requiresExactlyOneArg(t *testing.T) {
	c := client.NewClient("http://localhost:8080")
	cmd := GetCmd(c)

	// cobra.ExactArgs(1) validation
	if cmd.Args == nil {
		t.Error("expected Args validation to be set")
	}
}

// TestGetCmd_tableFormat_displaysCorrectly tests get command with table format output.
func TestGetCmd_tableFormat_displaysCorrectly(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/sessions/session-123" {
			t.Errorf("expected path /api/sessions/session-123, got %s", r.URL.Path)
		}
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"data":{"sessionId":"session-123","workflowIds":["workflow-1"]}}`))
	}))
	defer server.Close()

	c := client.NewClient(server.URL)
	cmd := GetCmd(c)

	commands.SetTestOutputFormat("table")
	defer commands.SetTestOutputFormat("table")

	r, w, _ := os.Pipe()
	oldStdout := os.Stdout
	os.Stdout = w

	err := cmd.RunE(cmd, []string{"session-123"})

	w.Close()
	os.Stdout = oldStdout

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	var buf bytes.Buffer
	_, _ = io.Copy(&buf, r)
	output := buf.String()

	if !strings.Contains(output, "session-123") {
		t.Errorf("expected 'session-123' in table output, got: %s", output)
	}
	if !strings.Contains(output, "Session ID") {
		t.Errorf("expected 'Session ID' header in output, got: %s", output)
	}
}

// TestGetCmd_jsonFormat_displaysCorrectly tests get command with JSON format output.
func TestGetCmd_jsonFormat_displaysCorrectly(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"data":{"sessionId":"session-123","workflowIds":["workflow-1"]}}`))
	}))
	defer server.Close()

	c := client.NewClient(server.URL)
	cmd := GetCmd(c)

	commands.SetTestOutputFormat("json")
	defer commands.SetTestOutputFormat("table")

	r, w, _ := os.Pipe()
	oldStdout := os.Stdout
	os.Stdout = w

	err := cmd.RunE(cmd, []string{"session-123"})

	w.Close()
	os.Stdout = oldStdout

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	var buf bytes.Buffer
	_, _ = io.Copy(&buf, r)
	output := buf.String()

	if !strings.Contains(output, "session-123") {
		t.Errorf("expected 'session-123' in JSON output, got: %s", output)
	}
}

// TestGetCmd_unsupportedFormat_returnsError tests handling of unsupported output format.
func TestGetCmd_unsupportedFormat_returnsError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"data":{"sessionId":"session-123"}}`))
	}))
	defer server.Close()

	c := client.NewClient(server.URL)
	cmd := GetCmd(c)

	commands.SetTestOutputFormat("unsupported")
	defer commands.SetTestOutputFormat("table")

	err := cmd.RunE(cmd, []string{"session-123"})

	if err == nil {
		t.Error("expected error for unsupported format, got nil")
	}

	if !strings.Contains(err.Error(), "unsupported output format") {
		t.Errorf("expected 'unsupported output format' in error, got: %v", err)
	}
}

// TestGetCmd_apiError_returnsError tests error handling for API errors.
func TestGetCmd_apiError_returnsError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNotFound)
		_, _ = w.Write([]byte("Not Found"))
	}))
	defer server.Close()

	c := client.NewClient(server.URL)
	cmd := GetCmd(c)

	commands.SetTestOutputFormat("table")
	defer commands.SetTestOutputFormat("table")

	err := cmd.RunE(cmd, []string{"session-123"})

	if err == nil {
		t.Error("expected error for API failure, got nil")
	}

	if !strings.Contains(err.Error(), "failed to get session details") {
		t.Errorf("expected 'failed to get session details' in error, got: %v", err)
	}
}

// TestGetCmd_withWorkflows_displaysWorkflowIds tests that workflow IDs are displayed correctly.
func TestGetCmd_withWorkflows_displaysWorkflowIds(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"data":{"sessionId":"session-123","workflowIds":["workflow-1","workflow-2","workflow-3"]}}`))
	}))
	defer server.Close()

	c := client.NewClient(server.URL)
	cmd := GetCmd(c)

	commands.SetTestOutputFormat("table")
	defer commands.SetTestOutputFormat("table")

	r, w, _ := os.Pipe()
	oldStdout := os.Stdout
	os.Stdout = w

	err := cmd.RunE(cmd, []string{"session-123"})

	w.Close()
	os.Stdout = oldStdout

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	var buf bytes.Buffer
	_, _ = io.Copy(&buf, r)
	output := buf.String()

	// Should contain workflow IDs in the output
	if !strings.Contains(output, "workflow-1") && !strings.Contains(output, "Workflow") {
		t.Errorf("expected workflow information in output, got: %s", output)
	}
}

// TestGetCmd_noWorkflows_stillWorks tests handling of session with no workflows.
func TestGetCmd_noWorkflows_stillWorks(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"data":{"sessionId":"session-123","workflowIds":[]}}`))
	}))
	defer server.Close()

	c := client.NewClient(server.URL)
	cmd := GetCmd(c)

	commands.SetTestOutputFormat("table")
	defer commands.SetTestOutputFormat("table")

	r, w, _ := os.Pipe()
	oldStdout := os.Stdout
	os.Stdout = w

	err := cmd.RunE(cmd, []string{"session-123"})

	w.Close()
	os.Stdout = oldStdout

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	var buf bytes.Buffer
	_, _ = io.Copy(&buf, r)
	output := buf.String()

	// Should still display session ID
	if !strings.Contains(output, "session-123") {
		t.Errorf("expected 'session-123' in output, got: %s", output)
	}
}

// TestGetCmd_commandHasRunE tests that command has RunE handler.
func TestGetCmd_commandHasRunE(t *testing.T) {
	c := client.NewClient("http://localhost:8080")
	cmd := GetCmd(c)

	if cmd.RunE == nil {
		t.Error("expected GetCmd to have RunE handler")
	}
}

// TestGetCmd_serverError_returnsError tests handling of 500 server error.
func TestGetCmd_serverError_returnsError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
		_, _ = w.Write([]byte("Internal Server Error"))
	}))
	defer server.Close()

	c := client.NewClient(server.URL)
	cmd := GetCmd(c)

	err := cmd.RunE(cmd, []string{"session-123"})

	if err == nil {
		t.Error("expected error for 500 response, got nil")
	}
}

// TestGetCmd_malformedResponse_returnsError tests handling of malformed JSON response.
func TestGetCmd_malformedResponse_returnsError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`not valid json`))
	}))
	defer server.Close()

	c := client.NewClient(server.URL)
	cmd := GetCmd(c)

	err := cmd.RunE(cmd, []string{"session-123"})

	if err == nil {
		t.Error("expected error for malformed JSON, got nil")
	}
}

// TestGetCmd_differentSessionIds tests with various session ID formats.
func TestGetCmd_differentSessionIds(t *testing.T) {
	testCases := []string{
		"session-1",
		"test-123",
		"my-workflow-session",
	}

	for _, sessionID := range testCases {
		t.Run(sessionID, func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				expectedPath := "/api/sessions/" + sessionID
				if r.URL.Path != expectedPath {
					t.Errorf("expected path %s, got %s", expectedPath, r.URL.Path)
				}
				w.WriteHeader(http.StatusOK)
				_, _ = w.Write([]byte(`{"data":{"sessionId":"` + sessionID + `"}}`))
			}))
			defer server.Close()

			c := client.NewClient(server.URL)
			cmd := GetCmd(c)

			commands.SetTestOutputFormat("table")
			defer commands.SetTestOutputFormat("table")

			err := cmd.RunE(cmd, []string{sessionID})
			if err != nil {
				t.Errorf("unexpected error for sessionID %s: %v", sessionID, err)
			}
		})
	}
}

// TestGetCmd_nonStringSessionId_usesArgumentSessionId tests handling of non-string sessionId in response.
func TestGetCmd_nonStringSessionId_usesArgumentSessionId(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		// Response has numeric sessionId instead of string
		_, _ = w.Write([]byte(`{"data":{"sessionId":12345,"workflowIds":[]}}`))
	}))
	defer server.Close()

	c := client.NewClient(server.URL)
	cmd := GetCmd(c)

	commands.SetTestOutputFormat("table")
	defer commands.SetTestOutputFormat("table")

	r, w, _ := os.Pipe()
	oldStdout := os.Stdout
	os.Stdout = w

	err := cmd.RunE(cmd, []string{"session-123"})

	w.Close()
	os.Stdout = oldStdout

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	var buf bytes.Buffer
	_, _ = io.Copy(&buf, r)
	output := buf.String()

	// Should use the argument sessionId when response value is not a string
	if !strings.Contains(output, "session-123") {
		t.Errorf("expected 'session-123' (from argument) in output, got: %s", output)
	}
}
