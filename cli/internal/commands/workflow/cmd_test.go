// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package workflow

import (
	"bytes"
	"fmt"
	"io"
	"os"
	"strings"
	"testing"
	"time"

	"com.infenia.yukta/go-cli/internal/client"
	"com.infenia.yukta/go-cli/internal/commands"
)

func TestStartCmd_successful(t *testing.T) {
	mockClient := &client.MockClient{
		StartWorkflowFunc: func(sessionID, workflowID string) (client.WorkflowStartResponse, error) {
			return client.WorkflowStartResponse{ExecutionID: "exec-123"}, nil
		},
	}

	cmd := StartCmd(mockClient)

	r, w, _ := os.Pipe()
	defer r.Close()
	oldStdout := os.Stdout
	os.Stdout = w

	err := cmd.RunE(cmd, []string{"session-1", "workflow-1"})

	w.Close()
	os.Stdout = oldStdout

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if mockClient.StartWorkflowCalls != 1 {
		t.Errorf("expected 1 call, got %d", mockClient.StartWorkflowCalls)
	}

	var buf bytes.Buffer
	_, _ = io.Copy(&buf, r)
	output := buf.String()
	if !strings.Contains(output, "exec-123") {
		t.Errorf("expected execution ID in output, got: %s", output)
	}
}

func TestStopCmd_successful(t *testing.T) {
	mockClient := &client.MockClient{
		StopWorkflowFunc: func(sessionID, workflowID string) (client.WorkflowStopResponse, error) {
			return client.WorkflowStopResponse{ExecutionIDs: []string{"exec-1", "exec-2"}}, nil
		},
	}

	cmd := StopCmd(mockClient)

	r, w, _ := os.Pipe()
	defer r.Close()
	oldStdout := os.Stdout
	os.Stdout = w

	err := cmd.RunE(cmd, []string{"session-1", "workflow-1"})

	w.Close()
	os.Stdout = oldStdout

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if mockClient.StopWorkflowCalls != 1 {
		t.Errorf("expected 1 call, got %d", mockClient.StopWorkflowCalls)
	}

	var buf bytes.Buffer
	_, _ = io.Copy(&buf, r)
	output := buf.String()
	if !strings.Contains(output, "2") {
		t.Errorf("expected execution count in output, got: %s", output)
	}
}

func TestPauseCmd_successful(t *testing.T) {
	mockClient := &client.MockClient{
		PauseWorkflowFunc: func(sessionID, executionID string) (client.WorkflowStartResponse, error) {
			return client.WorkflowStartResponse{ExecutionID: "exec-123"}, nil
		},
	}

	cmd := PauseCmd(mockClient)

	r, w, _ := os.Pipe()
	defer r.Close()
	oldStdout := os.Stdout
	os.Stdout = w

	err := cmd.RunE(cmd, []string{"session-1", "exec-123"})

	w.Close()
	os.Stdout = oldStdout

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if mockClient.PauseWorkflowCalls != 1 {
		t.Errorf("expected 1 call, got %d", mockClient.PauseWorkflowCalls)
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
		ResumeWorkflowFunc: func(sessionID, executionID string) (client.WorkflowStartResponse, error) {
			return client.WorkflowStartResponse{ExecutionID: "exec-123"}, nil
		},
	}

	cmd := ResumeCmd(mockClient)

	r, w, _ := os.Pipe()
	defer r.Close()
	oldStdout := os.Stdout
	os.Stdout = w

	err := cmd.RunE(cmd, []string{"session-1", "exec-123"})

	w.Close()
	os.Stdout = oldStdout

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if mockClient.ResumeWorkflowCalls != 1 {
		t.Errorf("expected 1 call, got %d", mockClient.ResumeWorkflowCalls)
	}

	var buf bytes.Buffer
	_, _ = io.Copy(&buf, r)
	output := buf.String()
	if !strings.Contains(output, "resumed") {
		t.Errorf("expected 'resumed' in output, got: %s", output)
	}
}

func TestPauseCmd_error(t *testing.T) {
	mockClient := &client.MockClient{
		PauseWorkflowFunc: func(sessionID, executionID string) (client.WorkflowStartResponse, error) {
			return client.WorkflowStartResponse{}, fmt.Errorf("pause failed")
		},
	}

	cmd := PauseCmd(mockClient)

	r, w, _ := os.Pipe()
	defer r.Close()
	oldStdout := os.Stdout
	os.Stdout = w

	err := cmd.RunE(cmd, []string{"session-1", "exec-123"})

	w.Close()
	os.Stdout = oldStdout
	_, _ = io.Copy(io.Discard, r)

	if err == nil {
		t.Fatal("expected error from PauseWorkflow")
	}
}

func TestPauseCmd_jsonOutput(t *testing.T) {
	mockClient := &client.MockClient{
		PauseWorkflowFunc: func(sessionID, executionID string) (client.WorkflowStartResponse, error) {
			return client.WorkflowStartResponse{ExecutionID: "exec-123"}, nil
		},
	}

	// Set output format to JSON
	oldFormat := commands.GetOutputFormat()
	commands.SetTestOutputFormat("json")
	defer commands.SetTestOutputFormat(oldFormat)

	cmd := PauseCmd(mockClient)

	r, w, _ := os.Pipe()
	defer r.Close()
	oldStdout := os.Stdout
	os.Stdout = w

	err := cmd.RunE(cmd, []string{"session-1", "exec-123"})

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

func TestResumeCmd_error(t *testing.T) {
	mockClient := &client.MockClient{
		ResumeWorkflowFunc: func(sessionID, executionID string) (client.WorkflowStartResponse, error) {
			return client.WorkflowStartResponse{}, fmt.Errorf("resume failed")
		},
	}

	cmd := ResumeCmd(mockClient)

	r, w, _ := os.Pipe()
	defer r.Close()
	oldStdout := os.Stdout
	os.Stdout = w

	err := cmd.RunE(cmd, []string{"session-1", "exec-123"})

	w.Close()
	os.Stdout = oldStdout
	_, _ = io.Copy(io.Discard, r)

	if err == nil {
		t.Fatal("expected error from ResumeWorkflow")
	}
}

func TestResumeCmd_jsonOutput(t *testing.T) {
	mockClient := &client.MockClient{
		ResumeWorkflowFunc: func(sessionID, executionID string) (client.WorkflowStartResponse, error) {
			return client.WorkflowStartResponse{ExecutionID: "exec-123"}, nil
		},
	}

	// Set output format to JSON
	oldFormat := commands.GetOutputFormat()
	commands.SetTestOutputFormat("json")
	defer commands.SetTestOutputFormat(oldFormat)

	cmd := ResumeCmd(mockClient)

	r, w, _ := os.Pipe()
	defer r.Close()
	oldStdout := os.Stdout
	os.Stdout = w

	err := cmd.RunE(cmd, []string{"session-1", "exec-123"})

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

func TestStatusCmd_successful(t *testing.T) {
	mockClient := &client.MockClient{
		GetWorkflowStatusFunc: func(sessionID, executionID string) (client.WorkflowProgress, error) {
			return client.WorkflowProgress{
				ExecutionID: "exec-123",
				Status:      "RUNNING",
				Tasks:       []client.TaskProgress{},
			}, nil
		},
	}

	cmd := StatusCmd(mockClient)

	r, w, _ := os.Pipe()
	defer r.Close()
	oldStdout := os.Stdout
	os.Stdout = w

	err := cmd.RunE(cmd, []string{"session-1", "exec-123"})

	w.Close()
	os.Stdout = oldStdout

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if mockClient.GetWorkflowStatusCalls != 1 {
		t.Errorf("expected 1 call, got %d", mockClient.GetWorkflowStatusCalls)
	}

	var buf bytes.Buffer
	_, _ = io.Copy(&buf, r)
	output := buf.String()
	if !strings.Contains(output, "RUNNING") {
		t.Errorf("expected status in output, got: %s", output)
	}
}

func TestHistoryCmd_successful(t *testing.T) {
	mockClient := &client.MockClient{
		GetWorkflowHistoryFunc: func(sessionID string) ([]client.WorkflowExecutionSummary, error) {
			return []client.WorkflowExecutionSummary{
				{ExecutionID: "exec-1", WorkflowID: "wf-1", Status: "COMPLETED"},
			}, nil
		},
	}

	cmd := HistoryCmd(mockClient)

	r, w, _ := os.Pipe()
	defer r.Close()
	oldStdout := os.Stdout
	os.Stdout = w

	err := cmd.RunE(cmd, []string{"session-1"})

	w.Close()
	os.Stdout = oldStdout

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if mockClient.GetWorkflowHistoryCalls != 1 {
		t.Errorf("expected 1 call, got %d", mockClient.GetWorkflowHistoryCalls)
	}

	var buf bytes.Buffer
	_, _ = io.Copy(&buf, r)
	output := buf.String()
	if !strings.Contains(output, "exec-1") {
		t.Errorf("expected execution in output, got: %s", output)
	}
}

func TestStopCmd_error(t *testing.T) {
	mockClient := &client.MockClient{
		StopWorkflowFunc: func(sessionID, workflowID string) (client.WorkflowStopResponse, error) {
			return client.WorkflowStopResponse{}, fmt.Errorf("stop failed")
		},
	}

	cmd := StopCmd(mockClient)

	r, w, _ := os.Pipe()
	defer r.Close()
	oldStdout := os.Stdout
	os.Stdout = w

	err := cmd.RunE(cmd, []string{"session-1", "workflow-1"})

	w.Close()
	os.Stdout = oldStdout
	_, _ = io.Copy(io.Discard, r)

	if err == nil {
		t.Fatal("expected error from StopWorkflow")
	}
}

func TestStopCmd_jsonOutput(t *testing.T) {
	mockClient := &client.MockClient{
		StopWorkflowFunc: func(sessionID, workflowID string) (client.WorkflowStopResponse, error) {
			return client.WorkflowStopResponse{ExecutionIDs: []string{"exec-1", "exec-2"}}, nil
		},
	}

	// Set output format to JSON
	oldFormat := commands.GetOutputFormat()
	commands.SetTestOutputFormat("json")
	defer commands.SetTestOutputFormat(oldFormat)

	cmd := StopCmd(mockClient)

	r, w, _ := os.Pipe()
	defer r.Close()
	oldStdout := os.Stdout
	os.Stdout = w

	err := cmd.RunE(cmd, []string{"session-1", "workflow-1"})

	w.Close()
	os.Stdout = oldStdout

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	var buf bytes.Buffer
	_, _ = io.Copy(&buf, r)
	output := buf.String()
	if !strings.Contains(output, "executionIds") {
		t.Errorf("expected JSON output, got: %s", output)
	}
}

func TestStatusCmd_error(t *testing.T) {
	mockClient := &client.MockClient{
		GetWorkflowStatusFunc: func(sessionID, executionID string) (client.WorkflowProgress, error) {
			return client.WorkflowProgress{}, fmt.Errorf("status failed")
		},
	}

	cmd := StatusCmd(mockClient)

	r, w, _ := os.Pipe()
	defer r.Close()
	oldStdout := os.Stdout
	os.Stdout = w

	err := cmd.RunE(cmd, []string{"session-1", "exec-123"})

	w.Close()
	os.Stdout = oldStdout
	_, _ = io.Copy(io.Discard, r)

	if err == nil {
		t.Fatal("expected error from GetWorkflowStatus")
	}
}

func TestStatusCmd_jsonOutput(t *testing.T) {
	mockClient := &client.MockClient{
		GetWorkflowStatusFunc: func(sessionID, executionID string) (client.WorkflowProgress, error) {
			return client.WorkflowProgress{
				ExecutionID: "exec-123",
				Status:      "COMPLETED",
				Tasks:       []client.TaskProgress{},
			}, nil
		},
	}

	// Set output format to JSON
	oldFormat := commands.GetOutputFormat()
	commands.SetTestOutputFormat("json")
	defer commands.SetTestOutputFormat(oldFormat)

	cmd := StatusCmd(mockClient)

	r, w, _ := os.Pipe()
	defer r.Close()
	oldStdout := os.Stdout
	os.Stdout = w

	err := cmd.RunE(cmd, []string{"session-1", "exec-123"})

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

func TestHistoryCmd_jsonOutput(t *testing.T) {
	mockClient := &client.MockClient{
		GetWorkflowHistoryFunc: func(sessionID string) ([]client.WorkflowExecutionSummary, error) {
			return []client.WorkflowExecutionSummary{
				{ExecutionID: "exec-1", WorkflowID: "wf-1", Status: "COMPLETED"},
			}, nil
		},
	}

	// Set output format to JSON
	oldFormat := commands.GetOutputFormat()
	commands.SetTestOutputFormat("json")
	defer commands.SetTestOutputFormat(oldFormat)

	cmd := HistoryCmd(mockClient)

	r, w, _ := os.Pipe()
	defer r.Close()
	oldStdout := os.Stdout
	os.Stdout = w

	err := cmd.RunE(cmd, []string{"session-1"})

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

func TestHistoryCmd_error(t *testing.T) {
	mockClient := &client.MockClient{
		GetWorkflowHistoryFunc: func(sessionID string) ([]client.WorkflowExecutionSummary, error) {
			return nil, fmt.Errorf("history failed")
		},
	}

	cmd := HistoryCmd(mockClient)

	r, w, _ := os.Pipe()
	defer r.Close()
	oldStdout := os.Stdout
	os.Stdout = w

	err := cmd.RunE(cmd, []string{"session-1"})

	w.Close()
	os.Stdout = oldStdout
	_, _ = io.Copy(io.Discard, r)

	if err == nil {
		t.Fatal("expected error from GetWorkflowHistory")
	}
}

func TestHistoryCmd_multipleExecutions(t *testing.T) {
	mockClient := &client.MockClient{
		GetWorkflowHistoryFunc: func(sessionID string) ([]client.WorkflowExecutionSummary, error) {
			startTime, _ := time.Parse(time.RFC3339, "2026-07-17T10:00:00Z")
			endTime, _ := time.Parse(time.RFC3339, "2026-07-17T10:05:00Z")
			startTime2, _ := time.Parse(time.RFC3339, "2026-07-17T10:10:00Z")
			endTime2, _ := time.Parse(time.RFC3339, "2026-07-17T10:15:00Z")
			return []client.WorkflowExecutionSummary{
				{ExecutionID: "exec-1", WorkflowID: "wf-1", Status: "COMPLETED", StartTime: startTime, EndTime: endTime},
				{ExecutionID: "exec-2", WorkflowID: "wf-1", Status: "FAILED", StartTime: startTime2, EndTime: endTime2},
			}, nil
		},
	}

	cmd := HistoryCmd(mockClient)

	r, w, _ := os.Pipe()
	defer r.Close()
	oldStdout := os.Stdout
	os.Stdout = w

	err := cmd.RunE(cmd, []string{"session-1"})

	w.Close()
	os.Stdout = oldStdout

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	var buf bytes.Buffer
	_, _ = io.Copy(&buf, r)
	output := buf.String()
	if !strings.Contains(output, "Total Executions: 2") {
		t.Errorf("expected 2 executions in output, got: %s", output)
	}
	if !strings.Contains(output, "exec-1") || !strings.Contains(output, "exec-2") {
		t.Errorf("expected both executions in output, got: %s", output)
	}
}

func TestStartCmd_error(t *testing.T) {
	mockClient := &client.MockClient{
		StartWorkflowFunc: func(sessionID, workflowID string) (client.WorkflowStartResponse, error) {
			return client.WorkflowStartResponse{}, fmt.Errorf("start failed")
		},
	}

	cmd := StartCmd(mockClient)

	r, w, _ := os.Pipe()
	defer r.Close()
	oldStdout := os.Stdout
	os.Stdout = w

	err := cmd.RunE(cmd, []string{"session-1", "workflow-1"})

	w.Close()
	os.Stdout = oldStdout
	_, _ = io.Copy(io.Discard, r)

	if err == nil {
		t.Fatal("expected error from StartWorkflow")
	}
}

func TestStartCmd_jsonOutput(t *testing.T) {
	mockClient := &client.MockClient{
		StartWorkflowFunc: func(sessionID, workflowID string) (client.WorkflowStartResponse, error) {
			return client.WorkflowStartResponse{ExecutionID: "exec-123"}, nil
		},
	}

	// Set output format to JSON
	oldFormat := commands.GetOutputFormat()
	commands.SetTestOutputFormat("json")
	defer commands.SetTestOutputFormat(oldFormat)

	cmd := StartCmd(mockClient)

	r, w, _ := os.Pipe()
	defer r.Close()
	oldStdout := os.Stdout
	os.Stdout = w

	err := cmd.RunE(cmd, []string{"session-1", "workflow-1"})

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

func TestWorkflowCmd_createsCommand(t *testing.T) {
	c := client.NewClient("http://localhost:8080")
	cmd := WorkflowCmd(c)

	if cmd.Use != "workflow" {
		t.Errorf("expected Use 'workflow', got %q", cmd.Use)
	}
}

func TestWorkflowCmd_registersSubcommands(t *testing.T) {
	c := client.NewClient("http://localhost:8080")
	cmd := WorkflowCmd(c)

	subcommands := cmd.Commands()
	// workflow should have: start, stop, pause, resume, status, status-stream, history, executions, node
	expectedCount := 9
	if len(subcommands) != expectedCount {
		t.Errorf("expected %d subcommands, got %d", expectedCount, len(subcommands))
	}

	// Verify key subcommands are present by checking if their Use starts with the expected name
	expectedCommands := []string{"start", "stop", "pause", "resume", "status", "status-stream", "history", "executions", "node"}
	foundCommands := make(map[string]bool)

	for _, sub := range subcommands {
		name := strings.Fields(sub.Use)[0]
		for _, expected := range expectedCommands {
			if name == expected {
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
