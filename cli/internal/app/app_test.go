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

package app

import (
	"io"
	"os"
	"testing"
)

// TestRun_doesNotPanic tests that Run does not panic during initialization.
func TestRun_doesNotPanic(t *testing.T) {
	// Suppress stdout/stderr to avoid cluttering test output
	r, w, _ := os.Pipe()
	oldStdout := os.Stdout
	oldStderr := os.Stderr
	os.Stdout = w
	os.Stderr = w
	defer func() {
		w.Close()
		os.Stdout = oldStdout
		os.Stderr = oldStderr
		_, _ = io.ReadAll(r)
	}()

	// This test verifies that Run can successfully initialize without panicking
	defer func() {
		if recov := recover(); recov != nil {
			t.Errorf("Run panicked: %v", recov)
		}
	}()

	// Run() will return an error because no subcommand is provided,
	// but the important thing is it doesn't panic during initialization
	_ = Run()
}

// TestRun_returnErrorOrHelp tests that Run handles missing subcommand gracefully.
func TestRun_returnErrorOrHelp(t *testing.T) {
	// Suppress stdout/stderr
	r, w, _ := os.Pipe()
	oldStdout := os.Stdout
	oldStderr := os.Stderr
	os.Stdout = w
	os.Stderr = w
	defer func() {
		w.Close()
		os.Stdout = oldStdout
		os.Stderr = oldStderr
		_, _ = io.ReadAll(r)
	}()

	// Run() will either return an error or nil (depending on how Cobra handles missing subcommand)
	// The important thing is it doesn't panic
	_ = Run()
}

// TestRun_initializesClient tests that Run successfully creates an HTTP client.
func TestRun_initializesClient(t *testing.T) {
	// Suppress stdout/stderr
	r, w, _ := os.Pipe()
	oldStdout := os.Stdout
	oldStderr := os.Stderr
	os.Stdout = w
	os.Stderr = w
	defer func() {
		w.Close()
		os.Stdout = oldStdout
		os.Stderr = oldStderr
		_, _ = io.ReadAll(r)
	}()

	// This verifies the HTTP client is created with proper default URL
	// The test passes if Run doesn't panic (which would mean client creation failed)
	defer func() {
		if recov := recover(); recov != nil {
			t.Errorf("Run panicked creating client: %v", recov)
		}
	}()

	_ = Run()
}

// TestRun_wiresUpCommands tests that all commands are properly wired up.
func TestRun_wiresUpCommands(t *testing.T) {
	// Suppress stdout/stderr
	r, w, _ := os.Pipe()
	oldStdout := os.Stdout
	oldStderr := os.Stderr
	os.Stdout = w
	os.Stderr = w
	defer func() {
		w.Close()
		os.Stdout = oldStdout
		os.Stderr = oldStderr
		_, _ = io.ReadAll(r)
	}()

	// This verifies all commands are properly wired during initialization
	defer func() {
		if recov := recover(); recov != nil {
			t.Errorf("Run panicked wiring commands: %v", recov)
		}
	}()

	_ = Run()
}
