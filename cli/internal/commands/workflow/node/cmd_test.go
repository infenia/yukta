// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package node

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

func TestPauseCmd_successful(t *testing.T) {
	mockClient := &client.MockClient{
		PauseNodeFunc: func(sessionID, executionID, nodeID string) (client.WorkflowStartResponse, error) {
			return client.WorkflowStartResponse{ExecutionID: "exec-123"}, nil
		},
	}

	cmd := PauseCmd(mockClient)

	r, w, _ := os.Pipe()
	oldStdout := os.Stdout
	os.Stdout = w

	err := cmd.RunE(cmd, []string{"session-1", "exec-123", "node-1"})

	w.Close()
	os.Stdout = oldStdout

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if mockClient.PauseNodeCalls != 1 {
		t.Errorf("expected 1 call, got %d", mockClient.PauseNodeCalls)
	}

	var buf bytes.Buffer
	_, _ = io.Copy(&buf, r)
	output := buf.String()
	if !strings.Contains(output, "paused") {
		t.Errorf("expected 'paused' in output, got: %s", output)
	}
}

func TestResumeCmd_successful(t *testing.T) {
	mockClient := &client.MockClient{
		ResumeNodeFunc: func(sessionID, executionID, nodeID string) (client.WorkflowStartResponse, error) {
			return client.WorkflowStartResponse{ExecutionID: "exec-123"}, nil
		},
	}

	cmd := ResumeCmd(mockClient)

	r, w, _ := os.Pipe()
	oldStdout := os.Stdout
	os.Stdout = w

	err := cmd.RunE(cmd, []string{"session-1", "exec-123", "node-1"})

	w.Close()
	os.Stdout = oldStdout

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if mockClient.ResumeNodeCalls != 1 {
		t.Errorf("expected 1 call, got %d", mockClient.ResumeNodeCalls)
	}

	var buf bytes.Buffer
	_, _ = io.Copy(&buf, r)
	output := buf.String()
	if !strings.Contains(output, "resumed") {
		t.Errorf("expected 'resumed' in output, got: %s", output)
	}
}

func TestStopCmd_successful(t *testing.T) {
	mockClient := &client.MockClient{
		StopNodeFunc: func(sessionID, executionID, nodeID string, immediate bool, reason string) (client.WorkflowStartResponse, error) {
			return client.WorkflowStartResponse{ExecutionID: "exec-123"}, nil
		},
	}

	cmd := StopCmd(mockClient)

	r, w, _ := os.Pipe()
	oldStdout := os.Stdout
	os.Stdout = w

	err := cmd.RunE(cmd, []string{"session-1", "exec-123", "node-1"})

	w.Close()
	os.Stdout = oldStdout

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if mockClient.StopNodeCalls != 1 {
		t.Errorf("expected 1 call, got %d", mockClient.StopNodeCalls)
	}

	var buf bytes.Buffer
	_, _ = io.Copy(&buf, r)
	output := buf.String()
	if !strings.Contains(output, "stopped") {
		t.Errorf("expected 'stopped' in output, got: %s", output)
	}
}

func TestSkipCmd_skipTrue(t *testing.T) {
	mockClient := &client.MockClient{
		SkipNodeFunc: func(sessionID, executionID, nodeID string, skip bool) (client.WorkflowStartResponse, error) {
			return client.WorkflowStartResponse{ExecutionID: "exec-123"}, nil
		},
	}

	cmd := SkipCmd(mockClient)

	r, w, _ := os.Pipe()
	oldStdout := os.Stdout
	os.Stdout = w

	err := cmd.RunE(cmd, []string{"session-1", "exec-123", "node-1"})

	w.Close()
	os.Stdout = oldStdout

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if mockClient.SkipNodeCalls != 1 {
		t.Errorf("expected 1 call, got %d", mockClient.SkipNodeCalls)
	}

	var buf bytes.Buffer
	_, _ = io.Copy(&buf, r)
	output := buf.String()
	if !strings.Contains(output, "skipped") {
		t.Errorf("expected 'skipped' in output, got: %s", output)
	}
}

func TestPauseCmd_error(t *testing.T) {
	mockClient := &client.MockClient{
		PauseNodeFunc: func(sessionID, executionID, nodeID string) (client.WorkflowStartResponse, error) {
			return client.WorkflowStartResponse{}, fmt.Errorf("pause failed")
		},
	}

	cmd := PauseCmd(mockClient)

	r, w, _ := os.Pipe()
	oldStdout := os.Stdout
	os.Stdout = w

	err := cmd.RunE(cmd, []string{"session-1", "exec-123", "node-1"})

	w.Close()
	os.Stdout = oldStdout
	_, _ = io.Copy(io.Discard, r)

	if err == nil {
		t.Fatal("expected error from PauseNode")
	}
}

func TestResumeCmd_error(t *testing.T) {
	mockClient := &client.MockClient{
		ResumeNodeFunc: func(sessionID, executionID, nodeID string) (client.WorkflowStartResponse, error) {
			return client.WorkflowStartResponse{}, fmt.Errorf("resume failed")
		},
	}

	cmd := ResumeCmd(mockClient)

	r, w, _ := os.Pipe()
	oldStdout := os.Stdout
	os.Stdout = w

	err := cmd.RunE(cmd, []string{"session-1", "exec-123", "node-1"})

	w.Close()
	os.Stdout = oldStdout
	_, _ = io.Copy(io.Discard, r)

	if err == nil {
		t.Fatal("expected error from ResumeNode")
	}
}

func TestStopCmd_error(t *testing.T) {
	mockClient := &client.MockClient{
		StopNodeFunc: func(sessionID, executionID, nodeID string, immediate bool, reason string) (client.WorkflowStartResponse, error) {
			return client.WorkflowStartResponse{}, fmt.Errorf("stop failed")
		},
	}

	cmd := StopCmd(mockClient)

	r, w, _ := os.Pipe()
	oldStdout := os.Stdout
	os.Stdout = w

	err := cmd.RunE(cmd, []string{"session-1", "exec-123", "node-1"})

	w.Close()
	os.Stdout = oldStdout
	_, _ = io.Copy(io.Discard, r)

	if err == nil {
		t.Fatal("expected error from StopNode")
	}
}

func TestPauseCmd_jsonOutput(t *testing.T) {
	mockClient := &client.MockClient{
		PauseNodeFunc: func(sessionID, executionID, nodeID string) (client.WorkflowStartResponse, error) {
			return client.WorkflowStartResponse{ExecutionID: "exec-123"}, nil
		},
	}

	// Set output format to JSON
	oldFormat := commands.GetOutputFormat()
	commands.SetTestOutputFormat("json")
	defer commands.SetTestOutputFormat(oldFormat)

	cmd := PauseCmd(mockClient)

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

func TestResumeCmd_jsonOutput(t *testing.T) {
	mockClient := &client.MockClient{
		ResumeNodeFunc: func(sessionID, executionID, nodeID string) (client.WorkflowStartResponse, error) {
			return client.WorkflowStartResponse{ExecutionID: "exec-123"}, nil
		},
	}

	// Set output format to JSON
	oldFormat := commands.GetOutputFormat()
	commands.SetTestOutputFormat("json")
	defer commands.SetTestOutputFormat(oldFormat)

	cmd := ResumeCmd(mockClient)

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

func TestStopCmd_jsonOutput(t *testing.T) {
	mockClient := &client.MockClient{
		StopNodeFunc: func(sessionID, executionID, nodeID string, immediate bool, reason string) (client.WorkflowStartResponse, error) {
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

func TestSkipCmd_error(t *testing.T) {
	mockClient := &client.MockClient{
		SkipNodeFunc: func(sessionID, executionID, nodeID string, skip bool) (client.WorkflowStartResponse, error) {
			return client.WorkflowStartResponse{}, fmt.Errorf("skip failed")
		},
	}

	cmd := SkipCmd(mockClient)

	r, w, _ := os.Pipe()
	oldStdout := os.Stdout
	os.Stdout = w

	err := cmd.RunE(cmd, []string{"session-1", "exec-123", "node-1"})

	w.Close()
	os.Stdout = oldStdout
	_, _ = io.Copy(io.Discard, r)

	if err == nil {
		t.Fatal("expected error from SkipNode")
	}
}

func TestSkipCmd_unskip(t *testing.T) {
	skipPassed := true // default value
	mockClient := &client.MockClient{
		SkipNodeFunc: func(sessionID, executionID, nodeID string, skip bool) (client.WorkflowStartResponse, error) {
			skipPassed = skip
			return client.WorkflowStartResponse{ExecutionID: "exec-123"}, nil
		},
	}

	cmd := SkipCmd(mockClient)

	r, w, _ := os.Pipe()
	oldStdout := os.Stdout
	os.Stdout = w

	// Need to parse flags first
	err := cmd.ParseFlags([]string{"--skip=false", "session-1", "exec-123", "node-1"})
	if err == nil {
		err = cmd.RunE(cmd, []string{"session-1", "exec-123", "node-1"})
	}

	w.Close()
	os.Stdout = oldStdout

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	if skipPassed != false {
		t.Errorf("expected skip to be false, got %v", skipPassed)
	}

	var buf bytes.Buffer
	_, _ = io.Copy(&buf, r)
	output := buf.String()
	if !strings.Contains(output, "unskipped") {
		t.Errorf("expected 'unskipped' in output, got: %s", output)
	}
}

func TestSkipCmd_jsonOutput(t *testing.T) {
	mockClient := &client.MockClient{
		SkipNodeFunc: func(sessionID, executionID, nodeID string, skip bool) (client.WorkflowStartResponse, error) {
			return client.WorkflowStartResponse{ExecutionID: "exec-123"}, nil
		},
	}

	// Set output format to JSON
	oldFormat := commands.GetOutputFormat()
	commands.SetTestOutputFormat("json")
	defer commands.SetTestOutputFormat(oldFormat)

	cmd := SkipCmd(mockClient)

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

func TestNodeCmd_createsCommand(t *testing.T) {
	c := client.NewClient("http://localhost:8080")
	cmd := NodeCmd(c)

	if cmd.Use != "node" {
		t.Errorf("expected Use 'node', got %q", cmd.Use)
	}
}

func TestNodeCmd_registersSubcommands(t *testing.T) {
	c := client.NewClient("http://localhost:8080")
	cmd := NodeCmd(c)

	subcommands := cmd.Commands()
	// node should have: pause, resume, stop, skip, step
	expectedCount := 5
	if len(subcommands) != expectedCount {
		t.Errorf("expected %d subcommands, got %d", expectedCount, len(subcommands))
	}

	// Verify key subcommands are present by checking if their Use starts with the expected name
	expectedCommands := []string{"pause", "resume", "stop", "skip", "step"}
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
