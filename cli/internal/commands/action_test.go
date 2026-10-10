// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package commands

import (
	"errors"
	"strings"
	"testing"

	"com.infenia.yukta/go-cli/internal/testutil"
)

func TestAction(t *testing.T) {
	spec := ActionSpec[string]{
		Use: "x <a>", Short: "s",
		Run: func(args []string) (string, error) {
			if args[0] == "bad" {
				return "", errors.New("boom")
			}
			return "resp-" + args[0], nil
		},
		Msg: func(resp string, args []string) string { return "done " + resp },
	}
	defer SetTestOutputFormat("table")

	run := func(format, arg string) (string, error) {
		SetTestOutputFormat(format)
		cmd := Action(spec)
		var err error
		out := testutil.Capture(t, func() { err = cmd.RunE(cmd, []string{arg}) })
		return out, err
	}

	if out, err := run("table", "a"); err != nil || strings.TrimSpace(out) != "done resp-a" {
		t.Errorf("table: %q %v", out, err)
	}
	if out, err := run("json", "a"); err != nil || strings.TrimSpace(out) != `"resp-a"` {
		t.Errorf("json: %q %v", out, err)
	}
	if _, err := run("table", "bad"); err == nil {
		t.Error("expected error from Run")
	}
}
