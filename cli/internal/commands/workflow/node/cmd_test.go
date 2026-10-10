// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package node

import (
	"strings"
	"testing"

	"com.infenia.yukta/go-cli/internal/commands"
	"com.infenia.yukta/go-cli/internal/testutil"
)

const ok = `{"data":{"executionId":"exec-1"}}`

func TestNodeCommands(t *testing.T) {
	tests := []struct {
		name    string
		args    []string
		wantReq string
		wantOut string
	}{
		{"pause", []string{"pause", "s", "e", "n"}, "POST /api/workflow/s/e/node/n/pause", "Node paused: n"},
		{"resume", []string{"resume", "s", "e", "n"}, "POST /api/workflow/s/e/node/n/resume", "Node resumed: n"},
		{"stop default", []string{"stop", "s", "e", "n"}, "POST /api/workflow/s/e/node/n/stop?immediate=false&reason=", "Node stopped: n"},
		{"stop flags", []string{"stop", "s", "e", "n", "--immediate", "--reason", "why"}, "POST /api/workflow/s/e/node/n/stop?immediate=true&reason=why", "Node stopped: n"},
		{"skip default", []string{"skip", "s", "e", "n"}, "POST /api/workflow/s/e/node/n/skip?skip=true", "Node n: skipped"},
		{"unskip", []string{"skip", "s", "e", "n", "--skip=false"}, "POST /api/workflow/s/e/node/n/skip?skip=false", "Node n: unskipped"},
		{"escapes ids", []string{"pause", "s", "e", "a/b"}, "POST /api/workflow/s/e/node/a%2Fb/pause", "Node paused: a/b"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			c, seen := testutil.Server(t, 200, ok)
			out, err := testutil.Run(t, NodeCmd(c), tt.args...)
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

func TestNodeCommands_jsonOutput(t *testing.T) {
	commands.SetTestOutputFormat("json")
	defer commands.SetTestOutputFormat("table")

	c, _ := testutil.Server(t, 200, ok)
	out, err := testutil.Run(t, NodeCmd(c), "pause", "s", "e", "n")
	if err != nil || !strings.Contains(out, `"executionId": "exec-1"`) {
		t.Errorf("out=%q err=%v", out, err)
	}
}

func TestNodeCommands_errors(t *testing.T) {
	c, _ := testutil.Server(t, 500, "boom")
	for _, args := range [][]string{
		{"pause", "s", "e", "n"}, {"resume", "s", "e", "n"}, {"stop", "s", "e", "n"},
		{"skip", "s", "e", "n"},
	} {
		if _, err := testutil.Run(t, NodeCmd(c), args...); err == nil || !strings.Contains(err.Error(), "500") {
			t.Errorf("%v: expected API error, got %v", args, err)
		}
	}
	// wrong number of arguments is rejected before any request
	if _, err := testutil.Run(t, NodeCmd(c), "pause", "s", "e"); err == nil {
		t.Error("expected arg-count error")
	}
}
