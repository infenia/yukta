// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package step

import (
	"strings"
	"testing"

	"com.infenia.yukta/go-cli/internal/testutil"
)

func TestStepCommands(t *testing.T) {
	tests := []struct {
		verb, wantReq, wantOut string
	}{
		{"enable", "POST /api/executions/e/nodes/n/step/enable", "Step mode enabled for node: n"},
		{"disable", "POST /api/executions/e/nodes/n/step/disable", "Step mode disabled for node: n"},
		{"next", "POST /api/executions/e/nodes/n/step", "Stepped to next execution in node: n"},
	}
	for _, tt := range tests {
		t.Run(tt.verb, func(t *testing.T) {
			c, seen := testutil.Server(t, 200, `{"data":{"executionId":"x"}}`)
			out, err := testutil.Run(t, StepCmd(c), tt.verb, "s", "e", "n")
			if err != nil {
				t.Fatal(err)
			}
			// A mutating call is preceded by the check that the execution belongs to the session.
			if n := len(*seen); n < 1 || n > 2 || (*seen)[n-1] != tt.wantReq {
				t.Errorf("requests %v, want %q last", *seen, tt.wantReq)
			}
			if strings.TrimSpace(out) != tt.wantOut {
				t.Errorf("output %q, want %q", out, tt.wantOut)
			}

			failing, _ := testutil.Server(t, 500, "boom")
			if _, err := testutil.Run(t, StepCmd(failing), tt.verb, "s", "e", "n"); err == nil {
				t.Error("expected API error")
			}
		})
	}
}
