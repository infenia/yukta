// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

// Package notify raises native desktop notifications by running the tool of the operating system:
// notify-send on Linux, osascript on macOS and PowerShell on Windows. It adds no dependency. Text
// always reaches the tool as an argument or an environment variable, never inside a shell string.
package notify

import (
	"context"
	"os/exec"
	"strings"
	"unicode"
)

// Item is what to show.
type Item struct {
	Title    string
	Body     string
	Severity string // INFO, WARN or ERROR
	Link     string // where a click goes, or empty
}

// Notifier raises one notification.
type Notifier interface {
	// Name is the tool that is run, for messages.
	Name() string
	// Notify shows the item.
	Notify(ctx context.Context, item Item) error
}

// Runner runs a program with arguments and extra environment variables, without a shell, and
// returns what it wrote to standard output.
type Runner func(ctx context.Context, env []string, name string, args ...string) (string, error)

// LookPath finds a program on the PATH, like exec.LookPath.
type LookPath func(name string) (string, error)

// ExecRunner runs a program with os/exec.
func ExecRunner(ctx context.Context, env []string, name string, args ...string) (string, error) {
	cmd := exec.CommandContext(ctx, name, args...) // #nosec G204 -- the program is a fixed tool, text is only arguments
	if len(env) > 0 {
		cmd.Env = append(cmd.Environ(), env...)
	}
	out, err := cmd.Output()
	return string(out), err
}

// Detect returns the notifier of an operating system (a GOOS name), or nil when its tool is not
// installed or the system has none.
func Detect(goos string, look LookPath, run Runner) Notifier {
	switch goos {
	case "linux":
		if _, err := look("notify-send"); err == nil {
			n := &sendNotifier{run: run}
			n.actions = n.supportsActions()
			return n
		}
	case "darwin":
		if _, err := look("osascript"); err == nil {
			return &scriptNotifier{run: run}
		}
	case "windows":
		if tool := powershell(look); tool != "" {
			return &toastNotifier{run: run, tool: tool}
		}
	}
	return nil
}

// powershell finds Windows PowerShell or PowerShell 7.
func powershell(look LookPath) string {
	for _, name := range []string{"powershell", "pwsh"} {
		if _, err := look(name); err == nil {
			return name
		}
	}
	return ""
}

// CleanBody is Clean for a text of several lines: line breaks stay.
func CleanBody(s string) string {
	return strings.Map(func(r rune) rune {
		if unicode.IsControl(r) && r != '\n' {
			return ' '
		}
		return r
	}, s)
}

// Clean drops control characters, so text from a workflow cannot move the cursor or fake a line.
func Clean(s string) string {
	return strings.Map(func(r rune) rune {
		if unicode.IsControl(r) {
			return ' '
		}
		return r
	}, s)
}

// Short cuts s to limit characters, marking the cut.
func Short(s string, limit int) string {
	r := []rune(s)
	if len(r) <= limit {
		return s
	}
	return string(r[:limit-1]) + "…"
}
