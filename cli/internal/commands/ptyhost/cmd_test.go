// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package ptyhost

import (
	"bytes"
	"errors"
	"io"
	"strings"
	"testing"
)

func run(t *testing.T, args ...string) (string, error) {
	t.Helper()
	pr, pw := io.Pipe()
	t.Cleanup(func() { _ = pw.Close() })
	cmd := PtyHostCmd()
	var out bytes.Buffer
	cmd.SetIn(pr)
	cmd.SetOut(&out)
	cmd.SetErr(io.Discard)
	cmd.SetArgs(args)
	err := cmd.Execute()
	return out.String(), err
}

func TestPtyHost_isHidden(t *testing.T) {
	if !PtyHostCmd().Hidden {
		t.Fatal("pty-host is shown in the help")
	}
}

func TestPtyHost_endsWithTheExitCodeOfTheProgram(t *testing.T) {
	out, err := run(t, "--cols", "40", "--rows", "10", "--", "sh", "-c", "echo hi; exit 3")

	var exit *ExitError
	if !errors.As(err, &exit) || exit.Code != 3 || exit.Error() == "" {
		t.Fatalf("err = %v", err)
	}
	if !strings.Contains(out, `"t":"exit","code":3`) {
		t.Fatalf("out = %q", out)
	}
}

func TestPtyHost_succeedsWhenTheProgramDoes(t *testing.T) {
	if _, err := run(t, "--", "sh", "-c", "exit 0"); err != nil {
		t.Fatalf("err = %v", err)
	}
}

func TestPtyHost_needsAProgramAfterTheDash(t *testing.T) {
	if _, err := run(t); err == nil {
		t.Fatal("ran with nothing to run")
	}
	if _, err := run(t, "sh"); err == nil {
		t.Fatal("took a program before --")
	}
}

func TestPtyHost_failsWhenTheProgramCannotStart(t *testing.T) {
	if _, err := run(t, "--", "/no/such/program"); err == nil || errors.As(err, new(*ExitError)) {
		t.Fatalf("err = %v", err)
	}
}
