// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package output

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"os"
	"strings"
	"testing"
)

func TestPrintTable(t *testing.T) {
	// Redirect stdout to capture output
	r, w, _ := os.Pipe()
	oldStdout := os.Stdout
	os.Stdout = w

	headers := []string{"Name", "Age", "City"}
	rows := [][]string{
		{"Alice", "30", "New York"},
		{"Bob", "25", "San Francisco"},
	}

	PrintTable(headers, rows)

	w.Close()
	os.Stdout = oldStdout

	// Read the captured output
	var buf bytes.Buffer
	if _, err := io.Copy(&buf, r); err != nil {
		t.Fatalf("Failed to read output: %v", err)
	}
	output := buf.String()

	// Verify output contains headers and data
	if !strings.Contains(output, "Name") {
		t.Errorf("Expected 'Name' in output, got: %s", output)
	}
	if !strings.Contains(output, "Alice") {
		t.Errorf("Expected 'Alice' in output, got: %s", output)
	}
	if !strings.Contains(output, "Bob") {
		t.Errorf("Expected 'Bob' in output, got: %s", output)
	}
}

func TestPrintJSON(t *testing.T) {
	// Redirect stdout to capture output
	r, w, _ := os.Pipe()
	oldStdout := os.Stdout
	os.Stdout = w

	data := map[string]interface{}{
		"name": "Alice",
		"age":  30,
	}

	PrintJSON(data)

	w.Close()
	os.Stdout = oldStdout

	// Read the captured output
	var buf bytes.Buffer
	if _, err := io.Copy(&buf, r); err != nil {
		t.Fatalf("Failed to read output: %v", err)
	}
	output := strings.TrimSpace(buf.String())

	// Verify output is valid JSON
	var result map[string]interface{}
	err := json.Unmarshal([]byte(output), &result)
	if err != nil {
		t.Errorf("Output is not valid JSON: %v", err)
	}

	// Verify content
	if result["name"] != "Alice" {
		t.Errorf("Expected name=Alice, got %v", result["name"])
	}
	if int(result["age"].(float64)) != 30 {
		t.Errorf("Expected age=30, got %v", result["age"])
	}
}

func TestPrintError(t *testing.T) {
	// Redirect stderr to capture output
	r, w, _ := os.Pipe()
	oldStderr := os.Stderr
	os.Stderr = w

	PrintError("test error message")

	w.Close()
	os.Stderr = oldStderr

	// Read the captured output
	var buf bytes.Buffer
	if _, err := io.Copy(&buf, r); err != nil {
		t.Fatalf("Failed to read output: %v", err)
	}
	output := buf.String()

	if !strings.Contains(output, "Error: test error message") {
		t.Errorf("Expected 'Error: test error message' in stderr, got: %s", output)
	}
}

// failingWriter is a mock writer that always fails on Write.
type failingWriter struct{}

func (f *failingWriter) Write(p []byte) (int, error) {
	return 0, errors.New("mock write error")
}

// TestPrintJSONToWriter_encodingError tests error handling when JSON encoding fails.
func TestPrintJSONToWriter_encodingError(t *testing.T) {
	// Redirect stderr to capture error output
	r, w, _ := os.Pipe()
	oldStderr := os.Stderr
	os.Stderr = w

	// Use a failing writer to trigger the error path
	failWriter := &failingWriter{}
	data := map[string]interface{}{
		"test": "data",
	}

	printJSONToWriter(failWriter, data)

	w.Close()
	os.Stderr = oldStderr

	// Read the captured stderr
	var buf bytes.Buffer
	if _, err := io.Copy(&buf, r); err != nil {
		t.Fatalf("Failed to read output: %v", err)
	}
	output := buf.String()

	// Verify error message was written to stderr
	if !strings.Contains(output, "Error encoding JSON") {
		t.Errorf("Expected 'Error encoding JSON' in stderr, got: %s", output)
	}
}
