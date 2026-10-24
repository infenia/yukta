// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package executions

import (
	"strings"
	"testing"

	"com.infenia.yukta/go-cli/internal/testutil"
)

func TestExecutionsCommands(t *testing.T) {
	tests := []struct {
		name    string
		args    []string
		wantReq string
		wantOut string
	}{
		{"stop", []string{"stop", "e1"}, "POST /api/executions/e1/stop", "Execution stopped: exec-1"},
		{"stop tree", []string{"stop", "e1", "--tree"}, "POST /api/executions/e1/stop?tree=true", "Execution stopped: exec-1"},
		{"restart", []string{"restart", "e1"}, "POST /api/executions/e1/restart", "Execution restarted with ID: exec-1"},
		{"restart from node", []string{"restart", "e1", "n1"}, "POST /api/executions/e1/restart/n1", "Execution restarted with ID: exec-1"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			c, seen := testutil.Server(t, 200, `{"data":{"executionId":"exec-1"}}`)
			out, err := testutil.Run(t, ExecutionsCmd(c), tt.args...)
			if err != nil {
				t.Fatal(err)
			}
			if len(*seen) != 1 || (*seen)[0] != tt.wantReq {
				t.Errorf("requests %v, want %q", *seen, tt.wantReq)
			}
			if strings.TrimSpace(out) != tt.wantOut {
				t.Errorf("output %q, want %q", out, tt.wantOut)
			}
		})
	}
}

func TestExecutionsCommands_errors(t *testing.T) {
	c, _ := testutil.Server(t, 500, "boom")
	for _, args := range [][]string{{"stop", "e"}, {"restart", "e"}, {"restart", "e", "n"}} {
		if _, err := testutil.Run(t, ExecutionsCmd(c), args...); err == nil {
			t.Errorf("%v: expected error", args)
		}
	}
}
