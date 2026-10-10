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
		{"enable", "POST /api/workflow/s/e/node/n/step/enable", "Step mode enabled for node: n"},
		{"disable", "POST /api/workflow/s/e/node/n/step/disable", "Step mode disabled for node: n"},
		{"next", "POST /api/workflow/s/e/node/n/step", "Stepped to next execution in node: n"},
	}
	for _, tt := range tests {
		t.Run(tt.verb, func(t *testing.T) {
			c, seen := testutil.Server(t, 200, `{"data":{"executionId":"x"}}`)
			out, err := testutil.Run(t, StepCmd(c), tt.verb, "s", "e", "n")
			if err != nil {
				t.Fatal(err)
			}
			if len(*seen) != 1 || (*seen)[0] != tt.wantReq {
				t.Errorf("requests %v, want %q", *seen, tt.wantReq)
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
