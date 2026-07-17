// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package logs

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"os"
	"strings"
	"testing"

	"com.infenia.yukta/go-cli/internal/client"
)

func TestLogsCmd_createsCommand(t *testing.T) {
	c := client.NewClient("http://localhost:8080")
	cmd := LogsCmd(c)

	if !strings.HasPrefix(cmd.Use, "logs") {
		t.Errorf("expected Use starting with 'logs', got %q", cmd.Use)
	}
}

func TestLogsCmd_executesSuccessfully(t *testing.T) {
	// Mock client that simulates streaming logs
	mockClient := &client.MockClient{
		StreamExecutionLogsFunc: func(ctx context.Context, sessionID, executionID string, onLine func(line string) error) error {
			if err := onLine("Log line 1"); err != nil {
				return err
			}
			if err := onLine("Log line 2"); err != nil {
				return err
			}
			if err := onLine("Log line 3"); err != nil {
				return err
			}
			return nil
		},
	}

	cmd := LogsCmd(mockClient)

	// Capture stdout
	r, w, _ := os.Pipe()
	defer r.Close()
	oldStdout := os.Stdout
	os.Stdout = w

	err := cmd.RunE(cmd, []string{"session-123", "exec-456"})

	w.Close()
	os.Stdout = oldStdout

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	// Read captured output
	var buf bytes.Buffer
	_, _ = io.Copy(&buf, r)
	output := buf.String()

	// Verify all log lines were printed
	if !strings.Contains(output, "Log line 1") {
		t.Errorf("expected 'Log line 1' in output, got: %s", output)
	}
	if !strings.Contains(output, "Log line 2") {
		t.Errorf("expected 'Log line 2' in output, got: %s", output)
	}
	if !strings.Contains(output, "Log line 3") {
		t.Errorf("expected 'Log line 3' in output, got: %s", output)
	}

	// Verify the client was called
	if mockClient.StreamExecutionLogsCalls != 1 {
		t.Errorf("expected 1 call to StreamExecutionLogs, got %d", mockClient.StreamExecutionLogsCalls)
	}
}

func TestLogsCmd_handlesError(t *testing.T) {
	mockClient := &client.MockClient{
		StreamExecutionLogsFunc: func(ctx context.Context, sessionID, executionID string, onLine func(line string) error) error {
			return context.Canceled
		},
	}

	cmd := LogsCmd(mockClient)

	// Capture stdout to suppress output
	r, w, _ := os.Pipe()
	defer r.Close()
	oldStdout := os.Stdout
	os.Stdout = w

	err := cmd.RunE(cmd, []string{"session-123", "exec-456"})

	w.Close()
	os.Stdout = oldStdout
	_, _ = io.Copy(io.Discard, r)

	// Context.Canceled should be treated as a clean exit
	if err != nil {
		t.Errorf("expected nil error for context.Canceled, got: %v", err)
	}
}

func TestLogsCmd_handlesWrappedCancellation(t *testing.T) {
	mockClient := &client.MockClient{
		StreamExecutionLogsFunc: func(ctx context.Context, sessionID, executionID string, onLine func(line string) error) error {
			return fmt.Errorf("request failed: %w", context.Canceled)
		},
	}

	cmd := LogsCmd(mockClient)

	r, w, _ := os.Pipe()
	defer r.Close()
	oldStdout := os.Stdout
	os.Stdout = w

	err := cmd.RunE(cmd, []string{"session-123", "exec-456"})

	w.Close()
	os.Stdout = oldStdout
	_, _ = io.Copy(io.Discard, r)

	// A wrapped context.Canceled should still be treated as a clean exit
	if err != nil {
		t.Errorf("expected nil error for wrapped context.Canceled, got: %v", err)
	}
}

func TestLogsCmd_callbackError(t *testing.T) {
	mockClient := &client.MockClient{
		StreamExecutionLogsFunc: func(ctx context.Context, sessionID, executionID string, onLine func(line string) error) error {
			// Return an error via context.Canceled to simulate callback error
			return context.Canceled
		},
	}

	cmd := LogsCmd(mockClient)

	r, w, _ := os.Pipe()
	defer r.Close()
	oldStdout := os.Stdout
	os.Stdout = w

	_ = cmd.RunE(cmd, []string{"session-123", "exec-456"})

	w.Close()
	os.Stdout = oldStdout
	_, _ = io.Copy(io.Discard, r)

	// Just verify the command runs without panicking
	// Context.Canceled is treated as a clean exit
}
