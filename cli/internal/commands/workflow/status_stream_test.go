// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package workflow

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"os"
	"strings"
	"testing"

	"com.infenia.yukta/go-cli/internal/client"
)

func TestStatusStreamCmd_successful(t *testing.T) {
	mockClient := &client.MockClient{
		StreamWorkflowStatusFunc: func(ctx context.Context, sessionID, executionID string, includeHistory bool, onProgress func(client.WorkflowProgress) error) error {
			// Simulate a single progress update
			return onProgress(client.WorkflowProgress{
				ExecutionID: executionID,
				SessionID:   sessionID,
				Status:      "RUNNING",
				Tasks:       []client.TaskProgress{},
			})
		},
	}

	cmd := StatusStreamCmd(mockClient)

	r, w, _ := os.Pipe()
	oldStdout := os.Stdout
	os.Stdout = w

	err := cmd.RunE(cmd, []string{"session-1", "exec-123"})

	w.Close()
	os.Stdout = oldStdout

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if mockClient.StreamWorkflowStatusCalls != 1 {
		t.Errorf("expected 1 call, got %d", mockClient.StreamWorkflowStatusCalls)
	}

	var buf bytes.Buffer
	_, _ = io.Copy(&buf, r)
	output := buf.String()
	if !strings.Contains(output, "RUNNING") {
		t.Errorf("expected 'RUNNING' in output, got: %s", output)
	}
}

func TestStatusStreamCmd_withIncludeHistory(t *testing.T) {
	includeHistoryPassed := false
	mockClient := &client.MockClient{
		StreamWorkflowStatusFunc: func(ctx context.Context, sessionID, executionID string, includeHistory bool, onProgress func(client.WorkflowProgress) error) error {
			includeHistoryPassed = includeHistory
			return onProgress(client.WorkflowProgress{
				ExecutionID: executionID,
				SessionID:   sessionID,
				Status:      "COMPLETED",
				Tasks:       []client.TaskProgress{},
			})
		},
	}

	cmd := StatusStreamCmd(mockClient)

	r, w, _ := os.Pipe()
	oldStdout := os.Stdout
	os.Stdout = w

	// Need to parse flags first when using --include-history
	err := cmd.ParseFlags([]string{"--include-history", "session-1", "exec-123"})
	if err == nil {
		err = cmd.RunE(cmd, []string{"session-1", "exec-123"})
	}

	w.Close()
	os.Stdout = oldStdout

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	if !includeHistoryPassed {
		t.Error("expected includeHistory to be true")
	}

	var buf bytes.Buffer
	_, _ = io.Copy(&buf, r)
	output := buf.String()
	if !strings.Contains(output, "COMPLETED") {
		t.Errorf("expected 'COMPLETED' in output, got: %s", output)
	}
}

func TestStatusStreamCmd_clientError(t *testing.T) {
	mockClient := &client.MockClient{
		StreamWorkflowStatusFunc: func(ctx context.Context, sessionID, executionID string, includeHistory bool, onProgress func(client.WorkflowProgress) error) error {
			return context.Canceled
		},
	}

	cmd := StatusStreamCmd(mockClient)

	r, w, _ := os.Pipe()
	oldStdout := os.Stdout
	os.Stdout = w

	err := cmd.RunE(cmd, []string{"session-1", "exec-123"})

	w.Close()
	os.Stdout = oldStdout
	_, _ = io.Copy(io.Discard, r)

	// Context.Canceled should be treated as a clean exit
	if err != nil {
		t.Errorf("expected nil error for context.Canceled, got: %v", err)
	}
}

func TestStatusStreamCmd_handlesWrappedCancellation(t *testing.T) {
	mockClient := &client.MockClient{
		StreamWorkflowStatusFunc: func(ctx context.Context, sessionID, executionID string, includeHistory bool, onProgress func(client.WorkflowProgress) error) error {
			return fmt.Errorf("request failed: %w", context.Canceled)
		},
	}

	cmd := StatusStreamCmd(mockClient)

	r, w, _ := os.Pipe()
	oldStdout := os.Stdout
	os.Stdout = w

	err := cmd.RunE(cmd, []string{"session-1", "exec-123"})

	w.Close()
	os.Stdout = oldStdout
	_, _ = io.Copy(io.Discard, r)

	// A wrapped context.Canceled should still be treated as a clean exit
	if err != nil {
		t.Errorf("expected nil error for wrapped context.Canceled, got: %v", err)
	}
}

func TestStatusStreamCmd_callbackError(t *testing.T) {
	sentinelErr := fmt.Errorf("callback failed")
	mockClient := &client.MockClient{
		StreamWorkflowStatusFunc: func(ctx context.Context, sessionID, executionID string, includeHistory bool, onProgress func(client.WorkflowProgress) error) error {
			return sentinelErr
		},
	}

	cmd := StatusStreamCmd(mockClient)

	r, w, _ := os.Pipe()
	oldStdout := os.Stdout
	os.Stdout = w

	err := cmd.RunE(cmd, []string{"session-1", "exec-123"})

	w.Close()
	os.Stdout = oldStdout
	_, _ = io.Copy(io.Discard, r)

	if !errors.Is(err, sentinelErr) {
		t.Fatalf("expected sentinel error, got: %v", err)
	}
}

func TestStatusStreamCmd_missingArguments(t *testing.T) {
	mockClient := &client.MockClient{}
	cmd := StatusStreamCmd(mockClient)

	// Cobra validates Args before calling RunE, so we need to trigger the validation
	// by using the proper command execution path
	err := cmd.Args(cmd, []string{"session-1"}) // Missing executionID

	if err == nil {
		t.Error("expected error for missing executionID argument")
	}
}

func TestStatusStreamCmd_noArguments(t *testing.T) {
	mockClient := &client.MockClient{}
	cmd := StatusStreamCmd(mockClient)

	// Cobra validates Args before calling RunE, so we need to trigger the validation
	err := cmd.Args(cmd, []string{}) // No arguments

	if err == nil {
		t.Error("expected error for missing arguments")
	}
}

func TestStatusStreamCmd_multipleProgressUpdates(t *testing.T) {
	progressCount := 0
	mockClient := &client.MockClient{
		StreamWorkflowStatusFunc: func(ctx context.Context, sessionID, executionID string, includeHistory bool, onProgress func(client.WorkflowProgress) error) error {
			// Simulate multiple progress updates
			if err := onProgress(client.WorkflowProgress{
				ExecutionID: executionID,
				SessionID:   sessionID,
				Status:      "RUNNING",
				Tasks:       []client.TaskProgress{},
			}); err != nil {
				return err
			}
			progressCount++

			if err := onProgress(client.WorkflowProgress{
				ExecutionID: executionID,
				SessionID:   sessionID,
				Status:      "COMPLETED",
				Tasks:       []client.TaskProgress{},
			}); err != nil {
				return err
			}
			progressCount++

			return nil
		},
	}

	cmd := StatusStreamCmd(mockClient)

	r, w, _ := os.Pipe()
	oldStdout := os.Stdout
	os.Stdout = w

	err := cmd.RunE(cmd, []string{"session-1", "exec-123"})

	w.Close()
	os.Stdout = oldStdout

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	if progressCount != 2 {
		t.Errorf("expected 2 progress updates, got %d", progressCount)
	}

	var buf bytes.Buffer
	_, _ = io.Copy(&buf, r)
	output := buf.String()

	// Should have both status updates in output
	if !strings.Contains(output, "RUNNING") {
		t.Errorf("expected 'RUNNING' in output, got: %s", output)
	}
	if !strings.Contains(output, "COMPLETED") {
		t.Errorf("expected 'COMPLETED' in output, got: %s", output)
	}
}
