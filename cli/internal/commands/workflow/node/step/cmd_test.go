// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package step

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

func TestEnableCmd_successful(t *testing.T) {
	mockClient := &client.MockClient{
		EnableStepModeFunc: func(sessionID, executionID, nodeID string) (client.WorkflowStartResponse, error) {
			return client.WorkflowStartResponse{ExecutionID: "exec-123"}, nil
		},
	}

	cmd := EnableCmd(mockClient)

	r, w, _ := os.Pipe()
	oldStdout := os.Stdout
	os.Stdout = w

	err := cmd.RunE(cmd, []string{"session-1", "exec-123", "node-1"})

	w.Close()
	os.Stdout = oldStdout

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if mockClient.EnableStepModeCalls != 1 {
		t.Errorf("expected 1 call, got %d", mockClient.EnableStepModeCalls)
	}

	var buf bytes.Buffer
	_, _ = io.Copy(&buf, r)
	output := buf.String()
	if !strings.Contains(output, "enabled") {
		t.Errorf("expected 'enabled' in output, got: %s", output)
	}
}

func TestDisableCmd_successful(t *testing.T) {
	mockClient := &client.MockClient{
		DisableStepModeFunc: func(sessionID, executionID, nodeID string) (client.WorkflowStartResponse, error) {
			return client.WorkflowStartResponse{ExecutionID: "exec-123"}, nil
		},
	}

	cmd := DisableCmd(mockClient)

	r, w, _ := os.Pipe()
	oldStdout := os.Stdout
	os.Stdout = w

	err := cmd.RunE(cmd, []string{"session-1", "exec-123", "node-1"})

	w.Close()
	os.Stdout = oldStdout

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if mockClient.DisableStepModeCalls != 1 {
		t.Errorf("expected 1 call, got %d", mockClient.DisableStepModeCalls)
	}

	var buf bytes.Buffer
	_, _ = io.Copy(&buf, r)
	output := buf.String()
	if !strings.Contains(output, "disabled") {
		t.Errorf("expected 'disabled' in output, got: %s", output)
	}
}

func TestNextCmd_successful(t *testing.T) {
	mockClient := &client.MockClient{
		StepNodeFunc: func(sessionID, executionID, nodeID string) (client.WorkflowStartResponse, error) {
			return client.WorkflowStartResponse{ExecutionID: "exec-123"}, nil
		},
	}

	cmd := NextCmd(mockClient)

	r, w, _ := os.Pipe()
	oldStdout := os.Stdout
	os.Stdout = w

	err := cmd.RunE(cmd, []string{"session-1", "exec-123", "node-1"})

	w.Close()
	os.Stdout = oldStdout

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if mockClient.StepNodeCalls != 1 {
		t.Errorf("expected 1 call, got %d", mockClient.StepNodeCalls)
	}

	var buf bytes.Buffer
	_, _ = io.Copy(&buf, r)
	output := buf.String()
	if !strings.Contains(output, "Stepped") {
		t.Errorf("expected 'Stepped' in output, got: %s", output)
	}
}

func TestEnableCmd_error(t *testing.T) {
	mockClient := &client.MockClient{
		EnableStepModeFunc: func(sessionID, executionID, nodeID string) (client.WorkflowStartResponse, error) {
			return client.WorkflowStartResponse{}, fmt.Errorf("enable failed")
		},
	}

	cmd := EnableCmd(mockClient)

	r, w, _ := os.Pipe()
	oldStdout := os.Stdout
	os.Stdout = w

	err := cmd.RunE(cmd, []string{"session-1", "exec-123", "node-1"})

	w.Close()
	os.Stdout = oldStdout
	_, _ = io.Copy(io.Discard, r)

	if err == nil {
		t.Fatal("expected error from EnableStepMode")
	}
}

func TestDisableCmd_error(t *testing.T) {
	mockClient := &client.MockClient{
		DisableStepModeFunc: func(sessionID, executionID, nodeID string) (client.WorkflowStartResponse, error) {
			return client.WorkflowStartResponse{}, fmt.Errorf("disable failed")
		},
	}

	cmd := DisableCmd(mockClient)

	r, w, _ := os.Pipe()
	oldStdout := os.Stdout
	os.Stdout = w

	err := cmd.RunE(cmd, []string{"session-1", "exec-123", "node-1"})

	w.Close()
	os.Stdout = oldStdout
	_, _ = io.Copy(io.Discard, r)

	if err == nil {
		t.Fatal("expected error from DisableStepMode")
	}
}

func TestNextCmd_error(t *testing.T) {
	mockClient := &client.MockClient{
		StepNodeFunc: func(sessionID, executionID, nodeID string) (client.WorkflowStartResponse, error) {
			return client.WorkflowStartResponse{}, fmt.Errorf("step failed")
		},
	}

	cmd := NextCmd(mockClient)

	r, w, _ := os.Pipe()
	oldStdout := os.Stdout
	os.Stdout = w

	err := cmd.RunE(cmd, []string{"session-1", "exec-123", "node-1"})

	w.Close()
	os.Stdout = oldStdout
	_, _ = io.Copy(io.Discard, r)

	if err == nil {
		t.Fatal("expected error from StepNode")
	}
}

func TestEnableCmd_jsonOutput(t *testing.T) {
	mockClient := &client.MockClient{
		EnableStepModeFunc: func(sessionID, executionID, nodeID string) (client.WorkflowStartResponse, error) {
			return client.WorkflowStartResponse{ExecutionID: "exec-123"}, nil
		},
	}

	// Set output format to JSON
	oldFormat := commands.GetOutputFormat()
	commands.SetTestOutputFormat("json")
	defer commands.SetTestOutputFormat(oldFormat)

	cmd := EnableCmd(mockClient)

	r, w, _ := os.Pipe()
	oldStdout := os.Stdout
	os.Stdout = w

	err := cmd.RunE(cmd, []string{"session-1", "exec-123", "node-1"})

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

func TestDisableCmd_jsonOutput(t *testing.T) {
	mockClient := &client.MockClient{
		DisableStepModeFunc: func(sessionID, executionID, nodeID string) (client.WorkflowStartResponse, error) {
			return client.WorkflowStartResponse{ExecutionID: "exec-123"}, nil
		},
	}

	// Set output format to JSON
	oldFormat := commands.GetOutputFormat()
	commands.SetTestOutputFormat("json")
	defer commands.SetTestOutputFormat(oldFormat)

	cmd := DisableCmd(mockClient)

	r, w, _ := os.Pipe()
	oldStdout := os.Stdout
	os.Stdout = w

	err := cmd.RunE(cmd, []string{"session-1", "exec-123", "node-1"})

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

func TestNextCmd_jsonOutput(t *testing.T) {
	mockClient := &client.MockClient{
		StepNodeFunc: func(sessionID, executionID, nodeID string) (client.WorkflowStartResponse, error) {
			return client.WorkflowStartResponse{ExecutionID: "exec-123"}, nil
		},
	}

	// Set output format to JSON
	oldFormat := commands.GetOutputFormat()
	commands.SetTestOutputFormat("json")
	defer commands.SetTestOutputFormat(oldFormat)

	cmd := NextCmd(mockClient)

	r, w, _ := os.Pipe()
	oldStdout := os.Stdout
	os.Stdout = w

	err := cmd.RunE(cmd, []string{"session-1", "exec-123", "node-1"})

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

func TestStepCmd_createsCommand(t *testing.T) {
	c := client.NewClient("http://localhost:8080")
	cmd := StepCmd(c)

	if cmd.Use != "step" {
		t.Errorf("expected Use 'step', got %q", cmd.Use)
	}
}

func TestStepCmd_registersSubcommands(t *testing.T) {
	c := client.NewClient("http://localhost:8080")
	cmd := StepCmd(c)

	subcommands := cmd.Commands()
	// step should have: enable, disable, next
	expectedCount := 3
	if len(subcommands) != expectedCount {
		t.Errorf("expected %d subcommands, got %d", expectedCount, len(subcommands))
	}

	// Verify key subcommands are present by checking if their Use starts with the expected name
	expectedCommands := []string{"enable", "disable", "next"}
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
