// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package executions

import (
	"bytes"
	"fmt"
	"io"
	"os"
	"strings"
	"testing"

	"com.infenia.yukta/go-cli/internal/client"
	"com.infenia.yukta/go-cli/internal/commands"
)

func TestStopCmd_successful(t *testing.T) {
	mockClient := &client.MockClient{
		StopExecutionFunc: func(executionID string) (client.WorkflowStartResponse, error) {
			return client.WorkflowStartResponse{ExecutionID: "exec-123"}, nil
		},
	}

	cmd := StopCmd(mockClient)

	r, w, _ := os.Pipe()
	oldStdout := os.Stdout
	os.Stdout = w

	err := cmd.RunE(cmd, []string{"exec-123"})

	w.Close()
	os.Stdout = oldStdout

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if mockClient.StopExecutionCalls != 1 {
		t.Errorf("expected 1 call, got %d", mockClient.StopExecutionCalls)
	}

	var buf bytes.Buffer
	_, _ = io.Copy(&buf, r)
	output := buf.String()
	if !strings.Contains(output, "stopped") {
		t.Errorf("expected 'stopped' in output, got: %s", output)
	}
}

func TestRestartCmd_restartExecution(t *testing.T) {
	mockClient := &client.MockClient{
		RestartExecutionFunc: func(executionID string) (client.WorkflowStartResponse, error) {
			return client.WorkflowStartResponse{ExecutionID: "exec-456"}, nil
		},
	}

	cmd := RestartCmd(mockClient)

	r, w, _ := os.Pipe()
	oldStdout := os.Stdout
	os.Stdout = w

	err := cmd.RunE(cmd, []string{"exec-123"})

	w.Close()
	os.Stdout = oldStdout

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if mockClient.RestartExecutionCalls != 1 {
		t.Errorf("expected RestartExecution call, got %d", mockClient.RestartExecutionCalls)
	}

	var buf bytes.Buffer
	_, _ = io.Copy(&buf, r)
	output := buf.String()
	if !strings.Contains(output, "restarted") {
		t.Errorf("expected 'restarted' in output, got: %s", output)
	}
}

func TestRestartCmd_restartFromNode(t *testing.T) {
	mockClient := &client.MockClient{
		RestartFromNodeFunc: func(executionID, fromNodeID string) (client.WorkflowStartResponse, error) {
			return client.WorkflowStartResponse{ExecutionID: "exec-456"}, nil
		},
	}

	cmd := RestartCmd(mockClient)

	r, w, _ := os.Pipe()
	oldStdout := os.Stdout
	os.Stdout = w

	err := cmd.RunE(cmd, []string{"exec-123", "node-5"})

	w.Close()
	os.Stdout = oldStdout

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if mockClient.RestartFromNodeCalls != 1 {
		t.Errorf("expected RestartFromNode call, got %d", mockClient.RestartFromNodeCalls)
	}

	var buf bytes.Buffer
	_, _ = io.Copy(&buf, r)
	output := buf.String()
	if !strings.Contains(output, "restarted") {
		t.Errorf("expected 'restarted' in output, got: %s", output)
	}
}

func TestStopCmd_error(t *testing.T) {
	mockClient := &client.MockClient{
		StopExecutionFunc: func(executionID string) (client.WorkflowStartResponse, error) {
			return client.WorkflowStartResponse{}, fmt.Errorf("stop failed")
		},
	}

	cmd := StopCmd(mockClient)

	r, w, _ := os.Pipe()
	oldStdout := os.Stdout
	os.Stdout = w

	err := cmd.RunE(cmd, []string{"exec-123"})

	w.Close()
	os.Stdout = oldStdout
	_, _ = io.Copy(io.Discard, r)

	if err == nil {
		t.Fatal("expected error from StopExecution")
	}
}

func TestStopCmd_jsonOutput(t *testing.T) {
	mockClient := &client.MockClient{
		StopExecutionFunc: func(executionID string) (client.WorkflowStartResponse, error) {
			return client.WorkflowStartResponse{ExecutionID: "exec-123"}, nil
		},
	}

	// Set output format to JSON
	oldFormat := commands.GetOutputFormat()
	commands.SetTestOutputFormat("json")
	defer commands.SetTestOutputFormat(oldFormat)

	cmd := StopCmd(mockClient)

	r, w, _ := os.Pipe()
	oldStdout := os.Stdout
	os.Stdout = w

	err := cmd.RunE(cmd, []string{"exec-123"})

	w.Close()
	os.Stdout = oldStdout

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	var buf bytes.Buffer
	_, _ = io.Copy(&buf, r)
	output := buf.String()
	if !strings.Contains(output, "executionId") {
		t.Errorf("expected JSON output, got: %s", output)
	}
}

func TestRestartCmd_error(t *testing.T) {
	mockClient := &client.MockClient{
		RestartExecutionFunc: func(executionID string) (client.WorkflowStartResponse, error) {
			return client.WorkflowStartResponse{}, fmt.Errorf("restart failed")
		},
	}

	cmd := RestartCmd(mockClient)

	r, w, _ := os.Pipe()
	oldStdout := os.Stdout
	os.Stdout = w

	err := cmd.RunE(cmd, []string{"exec-123"})

	w.Close()
	os.Stdout = oldStdout
	_, _ = io.Copy(io.Discard, r)

	if err == nil {
		t.Fatal("expected error from RestartExecution")
	}
}

func TestRestartCmd_jsonOutput(t *testing.T) {
	mockClient := &client.MockClient{
		RestartExecutionFunc: func(executionID string) (client.WorkflowStartResponse, error) {
			return client.WorkflowStartResponse{ExecutionID: "exec-456"}, nil
		},
	}

	// Set output format to JSON
	oldFormat := commands.GetOutputFormat()
	commands.SetTestOutputFormat("json")
	defer commands.SetTestOutputFormat(oldFormat)

	cmd := RestartCmd(mockClient)

	r, w, _ := os.Pipe()
	oldStdout := os.Stdout
	os.Stdout = w

	err := cmd.RunE(cmd, []string{"exec-123"})

	w.Close()
	os.Stdout = oldStdout

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	var buf bytes.Buffer
	_, _ = io.Copy(&buf, r)
	output := buf.String()
	if !strings.Contains(output, "executionId") {
		t.Errorf("expected JSON output, got: %s", output)
	}
}

func TestRestartCmd_restartFromNode_error(t *testing.T) {
	mockClient := &client.MockClient{
		RestartFromNodeFunc: func(executionID, fromNodeID string) (client.WorkflowStartResponse, error) {
			return client.WorkflowStartResponse{}, fmt.Errorf("restart from node failed")
		},
	}

	cmd := RestartCmd(mockClient)

	r, w, _ := os.Pipe()
	oldStdout := os.Stdout
	os.Stdout = w

	err := cmd.RunE(cmd, []string{"exec-123", "node-5"})

	w.Close()
	os.Stdout = oldStdout
	_, _ = io.Copy(io.Discard, r)

	if err == nil {
		t.Fatal("expected error from RestartFromNode")
	}
}

func TestExecutionsCmd_createsCommand(t *testing.T) {
	c := client.NewClient("http://localhost:8080")
	cmd := ExecutionsCmd(c)

	if cmd.Use != "executions" {
		t.Errorf("expected Use 'executions', got %q", cmd.Use)
	}
}

func TestExecutionsCmd_registersSubcommands(t *testing.T) {
	c := client.NewClient("http://localhost:8080")
	cmd := ExecutionsCmd(c)

	subcommands := cmd.Commands()
	// executions should have: stop, restart
	expectedCount := 2
	if len(subcommands) != expectedCount {
		t.Errorf("expected %d subcommands, got %d", expectedCount, len(subcommands))
	}

	// Verify key subcommands are present by checking if their Use starts with the expected name
	expectedCommands := []string{"stop", "restart"}
	foundCommands := make(map[string]bool)

	for _, sub := range subcommands {
		for _, expected := range expectedCommands {
			if strings.HasPrefix(sub.Use, expected) {
				foundCommands[expected] = true
			}
		}
	}

	for _, expected := range expectedCommands {
		if !foundCommands[expected] {
			t.Errorf("expected '%s' subcommand not found", expected)
		}
	}
}
