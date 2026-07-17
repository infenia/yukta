// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package controlbus

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"

	"com.infenia.yukta/go-cli/internal/client"
	"com.infenia.yukta/go-cli/internal/commands"
)

func TestAllNodesCmd_createsCommand(t *testing.T) {
	c := client.NewClient("http://localhost:8080")
	cmd := AllNodesCmd(c)

	if cmd.Use != "all-nodes" {
		t.Errorf("expected Use 'all-nodes', got %q", cmd.Use)
	}
}

func TestAllNodesCmd_executesSuccessfully_tableFormat(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != "GET" {
			t.Errorf("expected GET, got %s", r.Method)
		}
		if r.URL.Path != "/api/control/nodes" {
			t.Errorf("expected /api/control/nodes, got %s", r.URL.Path)
		}

		w.Header().Set("Content-Type", "application/json")
		response := map[string]interface{}{
			"data": []string{"global-node-1", "global-node-2"},
		}
		_ = json.NewEncoder(w).Encode(response)
	}))
	defer server.Close()

	c := client.NewClient(server.URL)
	cmd := AllNodesCmd(c)

	commands.SetTestOutputFormat("table")
	defer commands.SetTestOutputFormat("table")

	r, w, _ := os.Pipe()
	oldStdout := os.Stdout
	os.Stdout = w

	err := cmd.RunE(cmd, []string{})

	w.Close()
	os.Stdout = oldStdout

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	var buf bytes.Buffer
	_, _ = io.Copy(&buf, r)
	output := buf.String()

	if !strings.Contains(output, "global-node-1") {
		t.Errorf("expected 'global-node-1' in output, got: %s", output)
	}
}

func TestAllNodesCmd_executesSuccessfully_jsonFormat(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		response := map[string]interface{}{
			"data": []string{"global-node-1"},
		}
		_ = json.NewEncoder(w).Encode(response)
	}))
	defer server.Close()

	c := client.NewClient(server.URL)
	cmd := AllNodesCmd(c)

	commands.SetTestOutputFormat("json")
	defer commands.SetTestOutputFormat("table")

	r, w, _ := os.Pipe()
	oldStdout := os.Stdout
	os.Stdout = w

	err := cmd.RunE(cmd, []string{})

	w.Close()
	os.Stdout = oldStdout

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	var buf bytes.Buffer
	_, _ = io.Copy(&buf, r)
	output := buf.String()

	if !strings.Contains(output, "global-node-1") {
		t.Errorf("expected 'global-node-1' in JSON output, got: %s", output)
	}
}

func TestAllNodesCmd_handlesApiError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
		_, _ = w.Write([]byte("Internal server error"))
	}))
	defer server.Close()

	c := client.NewClient(server.URL)
	cmd := AllNodesCmd(c)

	err := cmd.RunE(cmd, []string{})

	if err == nil {
		t.Error("expected error for server error, got nil")
	}
}

func TestAllNodesCmd_emptyNodes_tableFormat(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		response := map[string]interface{}{
			"data": []string{},
		}
		_ = json.NewEncoder(w).Encode(response)
	}))
	defer server.Close()

	c := client.NewClient(server.URL)
	cmd := AllNodesCmd(c)

	commands.SetTestOutputFormat("table")
	defer commands.SetTestOutputFormat("table")

	r, w, _ := os.Pipe()
	oldStdout := os.Stdout
	os.Stdout = w

	err := cmd.RunE(cmd, []string{})

	w.Close()
	os.Stdout = oldStdout

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	var buf bytes.Buffer
	_, _ = io.Copy(&buf, r)
}

func TestAllNodesCmd_singleNode(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		response := map[string]interface{}{
			"data": []string{"single-node"},
		}
		_ = json.NewEncoder(w).Encode(response)
	}))
	defer server.Close()

	c := client.NewClient(server.URL)
	cmd := AllNodesCmd(c)

	commands.SetTestOutputFormat("table")
	defer commands.SetTestOutputFormat("table")

	r, w, _ := os.Pipe()
	oldStdout := os.Stdout
	os.Stdout = w

	err := cmd.RunE(cmd, []string{})

	w.Close()
	os.Stdout = oldStdout

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	var buf bytes.Buffer
	_, _ = io.Copy(&buf, r)
	output := buf.String()

	if !strings.Contains(output, "single-node") {
		t.Errorf("expected 'single-node' in output, got: %s", output)
	}
}

func TestAllNodesCmd_formatCheck(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		response := map[string]interface{}{
			"data": []string{"node1", "node2", "node3"},
		}
		_ = json.NewEncoder(w).Encode(response)
	}))
	defer server.Close()

	c := client.NewClient(server.URL)
	cmd := AllNodesCmd(c)

	for _, format := range []string{"table", "json", "table"} {
		commands.SetTestOutputFormat(format)
		r, w, _ := os.Pipe()
		oldStdout := os.Stdout
		os.Stdout = w

		err := cmd.RunE(cmd, []string{})

		w.Close()
		os.Stdout = oldStdout

		if err != nil {
			t.Fatalf("unexpected error with format %s: %v", format, err)
		}

		var buf bytes.Buffer
		_, _ = io.Copy(&buf, r)
		output := buf.String()

		if !strings.Contains(output, "node1") {
			t.Errorf("expected nodes in output with format %s, got: %s", format, output)
		}
	}

	commands.SetTestOutputFormat("table")
}

func TestAllNodesCmd_usingMockClient(t *testing.T) {
	testCases := []struct {
		name       string
		format     string
		nodes      []string
		shouldHave string
	}{
		{"json_single_node", "json", []string{"n1"}, "n1"},
		{"table_single_node", "table", []string{"n1"}, "n1"},
		{"json_multiple_nodes", "json", []string{"n1", "n2"}, "n2"},
		{"table_multiple_nodes", "table", []string{"n1", "n2"}, "n2"},
		{"json_empty", "json", []string{}, "nodes"},
		{"table_empty", "table", []string{}, "Node"},
		{"json_many_nodes", "json", []string{"node-1", "node-2", "node-3", "node-4", "node-5"}, "node-5"},
		{"table_many_nodes", "table", []string{"node-1", "node-2", "node-3", "node-4", "node-5"}, "node-5"},
	}

	for _, tc := range testCases {
		t.Run(tc.name, func(t *testing.T) {
			mockClient := &client.MockClient{
				GetAllActiveNodesFunc: func() ([]string, error) {
					return tc.nodes, nil
				},
			}

			cmd := AllNodesCmd(mockClient)
			commands.SetTestOutputFormat(tc.format)

			r, w, _ := os.Pipe()
			oldStdout := os.Stdout
			os.Stdout = w

			err := cmd.RunE(cmd, []string{})

			w.Close()
			os.Stdout = oldStdout

			if err != nil {
				t.Fatalf("unexpected error: %v", err)
			}

			var buf bytes.Buffer
			_, _ = io.Copy(&buf, r)
			output := buf.String()

			if !strings.Contains(output, tc.shouldHave) {
				t.Errorf("expected %q in output, got: %s", tc.shouldHave, output)
			}
		})
	}

	commands.SetTestOutputFormat("table")
}

func TestAllNodesCmd_loopExecution(t *testing.T) {
	largeNodeList := make([]string, 100)
	for i := 0; i < 100; i++ {
		largeNodeList[i] = "node-" + string(rune(i))
	}

	mockClient := &client.MockClient{
		GetAllActiveNodesFunc: func() ([]string, error) {
			return largeNodeList, nil
		},
	}

	cmd := AllNodesCmd(mockClient)
	commands.SetTestOutputFormat("table")

	r, w, _ := os.Pipe()
	oldStdout := os.Stdout
	os.Stdout = w

	err := cmd.RunE(cmd, []string{})

	w.Close()
	os.Stdout = oldStdout

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	var buf bytes.Buffer
	_, _ = io.Copy(&buf, r)
	output := buf.String()

	if output == "" {
		t.Error("expected output, got empty string")
	}
}

func TestAllNodesCmd_allBranchesExecuted(t *testing.T) {
	tests := []struct {
		name        string
		format      string
		nodes       []string
		expectedErr bool
	}{
		{"success_json", "json", []string{"n1", "n2"}, false},
		{"success_table", "table", []string{"n1", "n2"}, false},
		{"error_case_json", "json", nil, true},
		{"error_case_table", "table", nil, true},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			var callCount int
			mockClient := &client.MockClient{
				GetAllActiveNodesFunc: func() ([]string, error) {
					callCount++
					if tt.expectedErr {
						return nil, io.EOF
					}
					return tt.nodes, nil
				},
			}

			cmd := AllNodesCmd(mockClient)
			commands.SetTestOutputFormat(tt.format)

			_, w, _ := os.Pipe()
			oldStdout := os.Stdout
			os.Stdout = w

			err := cmd.RunE(cmd, []string{})

			w.Close()
			os.Stdout = oldStdout

			if tt.expectedErr && err == nil {
				t.Error("expected error, got nil")
			}
			if !tt.expectedErr && err != nil {
				t.Fatalf("unexpected error: %v", err)
			}

			if callCount != 1 {
				t.Errorf("expected 1 call to GetAllActiveNodes, got %d", callCount)
			}
		})
	}

	commands.SetTestOutputFormat("table")
}
