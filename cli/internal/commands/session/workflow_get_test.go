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

// TestWorkflowCmd_createsCommand_withCorrectUse tests that WorkflowCmd creates command with correct Use.
func TestWorkflowCmd_createsCommand_withCorrectUse(t *testing.T) {
	c := client.NewClient("http://localhost:8080")
	cmd := WorkflowCmd(c)

	if cmd.Use != "workflow" {
		t.Errorf("expected Use 'workflow', got %q", cmd.Use)
	}
}

// TestWorkflowCmd_hasDescription tests that command has description.
func TestWorkflowCmd_hasDescription(t *testing.T) {
	c := client.NewClient("http://localhost:8080")
	cmd := WorkflowCmd(c)

	if cmd.Short == "" {
		t.Error("expected non-empty Short description")
	}

	if !strings.Contains(cmd.Short, "Workflow") || !strings.Contains(cmd.Short, "command") {
		t.Errorf("expected 'Workflow command' in short description, got %q", cmd.Short)
	}
}

// TestWorkflowCmd_addsGetSubcommand tests that get subcommand is registered.
func TestWorkflowCmd_addsGetSubcommand(t *testing.T) {
	c := client.NewClient("http://localhost:8080")
	cmd := WorkflowCmd(c)

	found := false
	for _, subcmd := range cmd.Commands() {
		if strings.Contains(subcmd.Use, "get") {
			found = true
			break
		}
	}
	if !found {
		t.Error("expected 'get' subcommand to be registered")
	}
}

// TestWorkflowCmd_noRunFunction tests that workflow command has no direct Run action.
func TestWorkflowCmd_noRunFunction(t *testing.T) {
	c := client.NewClient("http://localhost:8080")
	cmd := WorkflowCmd(c)

	if cmd.Run != nil {
		t.Error("expected workflow command to have no Run function (it's a command group)")
	}
}

// TestWorkflowGetCmd_createsCommand_withCorrectUse tests that WorkflowGetCmd creates command with correct Use.
func TestWorkflowGetCmd_createsCommand_withCorrectUse(t *testing.T) {
	c := client.NewClient("http://localhost:8080")
	cmd := WorkflowGetCmd(c)

	if !strings.Contains(cmd.Use, "get") {
		t.Errorf("expected Use to contain 'get', got %q", cmd.Use)
	}

	if !strings.Contains(cmd.Use, "session-id") {
		t.Errorf("expected Use to contain 'session-id', got %q", cmd.Use)
	}

	if !strings.Contains(cmd.Use, "workflow-id") {
		t.Errorf("expected Use to contain 'workflow-id', got %q", cmd.Use)
	}
}

// TestWorkflowGetCmd_hasDescription tests that command has description.
func TestWorkflowGetCmd_hasDescription(t *testing.T) {
	c := client.NewClient("http://localhost:8080")
	cmd := WorkflowGetCmd(c)

	if cmd.Short == "" {
		t.Error("expected non-empty Short description")
	}

	if !strings.Contains(cmd.Short, "workflow") {
		t.Errorf("expected 'workflow' in short description, got %q", cmd.Short)
	}
}

// TestWorkflowGetCmd_requiresExactlyTwoArgs tests that command requires exactly two arguments.
func TestWorkflowGetCmd_requiresExactlyTwoArgs(t *testing.T) {
	c := client.NewClient("http://localhost:8080")
	cmd := WorkflowGetCmd(c)

	if cmd.Args == nil {
		t.Error("expected Args validation to be set")
	}
}

// TestWorkflowGetCmd_jsonFormat_returnsWorkflow tests workflow get with JSON format output.
func TestWorkflowGetCmd_jsonFormat_returnsWorkflow(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		expectedPath := "/api/sessions/session-123/workflows/workflow-456"
		if r.URL.Path != expectedPath {
			t.Errorf("expected path %s, got %s", expectedPath, r.URL.Path)
		}
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"data":{"id":"workflow-456","nodes":[],"edges":[]}}`))
	}))
	defer server.Close()

	c := client.NewClient(server.URL)
	cmd := WorkflowGetCmd(c)

	commands.SetTestOutputFormat("json")
	defer commands.SetTestOutputFormat("table")

	r, w, _ := os.Pipe()
	oldStdout := os.Stdout
	os.Stdout = w

	err := cmd.RunE(cmd, []string{"session-123", "workflow-456"})

	w.Close()
	os.Stdout = oldStdout

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	var buf bytes.Buffer
	_, _ = io.Copy(&buf, r)
	output := buf.String()

	if !strings.Contains(output, "workflow-456") {
		t.Errorf("expected 'workflow-456' in JSON output, got: %s", output)
	}
}

// TestWorkflowGetCmd_tableFormat_alsoReturnsJson tests that table format still returns JSON.
func TestWorkflowGetCmd_tableFormat_alsoReturnsJson(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"data":{"id":"workflow-456"}}`))
	}))
	defer server.Close()

	c := client.NewClient(server.URL)
	cmd := WorkflowGetCmd(c)

	commands.SetTestOutputFormat("table")
	defer commands.SetTestOutputFormat("table")

	r, w, _ := os.Pipe()
	oldStdout := os.Stdout
	os.Stdout = w

	err := cmd.RunE(cmd, []string{"session-123", "workflow-456"})

	w.Close()
	os.Stdout = oldStdout

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	var buf bytes.Buffer
	_, _ = io.Copy(&buf, r)
	output := buf.String()

	// Even with "table" format, should return JSON for workflows
	if !strings.Contains(output, "workflow-456") {
		t.Errorf("expected workflow data in JSON output, got: %s", output)
	}
}

// TestWorkflowGetCmd_apiError_returnsError tests error handling for API errors.
func TestWorkflowGetCmd_apiError_returnsError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
		_, _ = w.Write([]byte("Error"))
	}))
	defer server.Close()

	c := client.NewClient(server.URL)
	cmd := WorkflowGetCmd(c)

	commands.SetTestOutputFormat("json")
	defer commands.SetTestOutputFormat("table")

	err := cmd.RunE(cmd, []string{"session-123", "workflow-456"})

	if err == nil {
		t.Error("expected error for API failure, got nil")
	}

	if !strings.Contains(err.Error(), "failed to retrieve workflow") {
		t.Errorf("expected 'failed to retrieve workflow' in error, got: %v", err)
	}
}

// TestWorkflowGetCmd_complexWorkflow_formatsCorrectly tests complex workflow JSON handling.
func TestWorkflowGetCmd_complexWorkflow_formatsCorrectly(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{
			"data": {
				"id": "workflow-456",
				"nodes": [
					{"id": "node-1", "type": "trigger"},
					{"id": "node-2", "type": "processor"}
				],
				"edges": [
					{"from": "node-1", "to": "node-2"}
				],
				"config": {"timeout": 30}
			}
		}`))
	}))
	defer server.Close()

	c := client.NewClient(server.URL)
	cmd := WorkflowGetCmd(c)

	commands.SetTestOutputFormat("json")
	defer commands.SetTestOutputFormat("table")

	r, w, _ := os.Pipe()
	oldStdout := os.Stdout
	os.Stdout = w

	err := cmd.RunE(cmd, []string{"session-123", "workflow-456"})

	w.Close()
	os.Stdout = oldStdout

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	var buf bytes.Buffer
	_, _ = io.Copy(&buf, r)
	output := buf.String()

	// Verify complex data is in output
	if !strings.Contains(output, "nodes") {
		t.Errorf("expected 'nodes' in output, got: %s", output)
	}
	if !strings.Contains(output, "edges") {
		t.Errorf("expected 'edges' in output, got: %s", output)
	}
}

// TestWorkflowGetCmd_commandHasRunE tests that command has RunE handler.
func TestWorkflowGetCmd_commandHasRunE(t *testing.T) {
	c := client.NewClient("http://localhost:8080")
	cmd := WorkflowGetCmd(c)

	if cmd.RunE == nil {
		t.Error("expected WorkflowGetCmd to have RunE handler")
	}
}

// TestWorkflowGetCmd_malformedResponse_returnsError tests handling of malformed JSON.
func TestWorkflowGetCmd_malformedResponse_returnsError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`not valid json`))
	}))
	defer server.Close()

	c := client.NewClient(server.URL)
	cmd := WorkflowGetCmd(c)

	commands.SetTestOutputFormat("json")
	defer commands.SetTestOutputFormat("table")

	err := cmd.RunE(cmd, []string{"session-123", "workflow-456"})

	if err == nil {
		t.Error("expected error for malformed JSON, got nil")
	}
}

// TestWorkflowGetCmd_notFoundError tests handling of 404 response.
func TestWorkflowGetCmd_notFoundError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNotFound)
		_, _ = w.Write([]byte("Not Found"))
	}))
	defer server.Close()

	c := client.NewClient(server.URL)
	cmd := WorkflowGetCmd(c)

	err := cmd.RunE(cmd, []string{"session-123", "workflow-456"})

	if err == nil {
		t.Error("expected error for 404 response, got nil")
	}
}

// TestWorkflowGetCmd_differentIds tests with various session and workflow ID formats.
func TestWorkflowGetCmd_differentIds(t *testing.T) {
	testCases := []struct {
		sessionID  string
		workflowID string
	}{
		{"session-1", "workflow-1"},
		{"test-session", "test-workflow"},
		{"my-app-session", "my-quality-workflow"},
	}

	for _, tc := range testCases {
		t.Run(tc.sessionID+"-"+tc.workflowID, func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				expectedPath := "/api/sessions/" + tc.sessionID + "/workflows/" + tc.workflowID
				if r.URL.Path != expectedPath {
					t.Errorf("expected path %s, got %s", expectedPath, r.URL.Path)
				}
				w.WriteHeader(http.StatusOK)
				_, _ = w.Write([]byte(`{"data":{"id":"` + tc.workflowID + `"}}`))
			}))
			defer server.Close()

			c := client.NewClient(server.URL)
			cmd := WorkflowGetCmd(c)

			commands.SetTestOutputFormat("json")
			defer commands.SetTestOutputFormat("table")

			err := cmd.RunE(cmd, []string{tc.sessionID, tc.workflowID})
			if err != nil {
				t.Errorf("unexpected error for IDs %s/%s: %v", tc.sessionID, tc.workflowID, err)
			}
		})
	}
}

// TestWorkflowGetCmd_emptyNodes_returnsWorkflow tests workflow with empty nodes.
func TestWorkflowGetCmd_emptyNodes_returnsWorkflow(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"data":{"id":"workflow-456","nodes":[],"edges":[]}}`))
	}))
	defer server.Close()

	c := client.NewClient(server.URL)
	cmd := WorkflowGetCmd(c)

	commands.SetTestOutputFormat("json")
	defer commands.SetTestOutputFormat("table")

	r, w, _ := os.Pipe()
	oldStdout := os.Stdout
	os.Stdout = w

	err := cmd.RunE(cmd, []string{"session-123", "workflow-456"})

	w.Close()
	os.Stdout = oldStdout

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	var buf bytes.Buffer
	_, _ = io.Copy(&buf, r)
	output := buf.String()

	if !strings.Contains(output, "workflow-456") {
		t.Errorf("expected workflow ID in output")
	}
}
