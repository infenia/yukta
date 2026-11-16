// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package notifications

import (
	"context"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"os/signal"
	"regexp"
	"runtime"
	"strings"
	"syscall"

	"com.infenia.yukta/go-cli/internal/client"
	"com.infenia.yukta/go-cli/internal/notify"
	"github.com/spf13/cobra"
)

// environment is what notify needs from the machine; tests replace it.
type environment struct {
	goos    string
	look    notify.LookPath
	run     notify.Runner
	context func() (context.Context, context.CancelFunc)
	errOut  func() io.Writer
}

// systemEnvironment is the real machine.
func systemEnvironment() environment {
	return environment{
		goos: runtime.GOOS,
		look: exec.LookPath,
		run:  notify.ExecRunner,
		context: func() (context.Context, context.CancelFunc) {
			return signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
		},
		errOut: func() io.Writer { return os.Stderr },
	}
}

// severities orders the levels, lowest first.
var severities = map[string]int{"INFO": 0, "WARN": 1, "ERROR": 2}

// NotifyCmd creates the "notify" command.
func NotifyCmd(c *client.Client) *cobra.Command {
	return notifyCmd(c, systemEnvironment())
}

func notifyCmd(c *client.Client, env environment) *cobra.Command {
	var minSeverity, kind, session, execLine string
	var printOnly bool
	cmd := &cobra.Command{
		Use:   "notify [--min-severity WARN] [--kind ACTION] [--session ID] [--print] [--exec 'cmd {{.Title}}']",
		Short: "Raise a desktop notification for each new item of your inbox",
		Long: `Follow your notification stream and raise one native desktop notification per new item, without
a browser: notify-send on Linux, osascript on macOS and PowerShell on Windows. With no such tool
found, or with --print, it prints one line per item instead. It reconnects by itself and, after a
gap, the server replays what you missed.

It honours your preferences on the server: a category set to OFF or INBOX, and muted sessions,
raise nothing here. For a question the text ends with the ready answer command; a notification
never answers by itself.

--exec runs your own command for each item instead, with no shell: the command line is split into
words first and each word may use {{.Title}}, {{.Body}}, {{.Severity}}, {{.Kind}}, {{.Category}},
{{.ID}}, {{.Session}}, {{.Execution}}, {{.Request}}, {{.Link}} and {{.Answer}}. That covers tmux, a
status bar or a phone push service.

To run it at login see the "Notifications from the terminal" page of the documentation (a systemd
user unit and a launchd agent).`,
		Args: cobra.NoArgs,
		RunE: func(cmd *cobra.Command, args []string) error {
			level, ok := severities[strings.ToUpper(minSeverity)]
			if !ok {
				return fmt.Errorf("--min-severity must be INFO, WARN or ERROR, not %q", minSeverity)
			}
			w := &watcher{
				c: c, env: env, out: cmd.OutOrStdout(), errOut: env.errOut(),
				level: level, kind: strings.ToUpper(kind), session: session, printOnly: printOnly,
			}
			if execLine != "" {
				command, err := notify.ParseCommand(execLine)
				if err != nil {
					return err
				}
				w.command = command
			}
			ctx, stop := env.context()
			defer stop()
			err := w.run(ctx)
			if errors.Is(err, context.Canceled) {
				return nil
			}
			return err
		},
	}
	cmd.Flags().StringVar(&minSeverity, "min-severity", "INFO", "Only this severity or above: INFO, WARN or ERROR")
	cmd.Flags().StringVar(&kind, "kind", "", "Only INFO or only ACTION")
	cmd.Flags().StringVar(&session, "session", "", "Only this session")
	cmd.Flags().BoolVar(&printOnly, "print", false, "Print a line per item instead of a desktop notification")
	cmd.Flags().StringVar(&execLine, "exec", "", "Run this command for each item instead (no shell)")
	return cmd
}

// watcher follows the stream and shows what is new.
type watcher struct {
	c         *client.Client
	env       environment
	out       io.Writer
	errOut    io.Writer
	level     int
	kind      string
	session   string
	printOnly bool
	command   *notify.Command
	notifier  notify.Notifier
	prefs     client.NotificationPreferences
}

func (w *watcher) run(ctx context.Context) error {
	if w.command == nil && !w.printOnly {
		w.notifier = notify.Detect(w.env.goos, w.env.look, w.env.run)
		if w.notifier == nil {
			fmt.Fprintln(w.errOut, "No desktop notifier found (notify-send, osascript or PowerShell): printing instead.")
			w.printOnly = true
		}
	}
	w.refreshPreferences()
	return w.c.StreamNotifications(ctx, func(event client.NotificationEvent) error {
		switch {
		case event.Type == "reset":
			w.refreshPreferences()
		case event.Type == "created" && event.Notification != nil:
			w.show(ctx, *event.Notification)
		}
		return nil
	})
}

// refreshPreferences reads the preferences again; without them everything notifies.
func (w *watcher) refreshPreferences() {
	if prefs, err := w.c.GetNotificationPreferences(); err == nil {
		w.prefs = prefs
	}
}

// wanted tells whether an item passes the filters of the command and the preferences.
func (w *watcher) wanted(n client.Notification) bool {
	return !n.Read &&
		severities[n.Severity] >= w.level &&
		(w.kind == "" || n.Kind == w.kind) &&
		(w.session == "" || n.SessionID == w.session) &&
		w.prefs.Wants(n)
}

// show raises one notification; a tool that fails is reported and the stream goes on.
func (w *watcher) show(ctx context.Context, n client.Notification) {
	if !w.wanted(n) {
		return
	}
	fields := w.fields(n)
	var err error
	switch {
	case w.command != nil:
		err = w.command.Run(ctx, w.env.run, fields)
	case w.printOnly:
		fmt.Fprintln(w.out, line(fields))
	default:
		err = w.notifier.Notify(ctx, notify.Item{
			Title: fields.Title, Body: text(fields), Severity: n.Severity, Link: fields.Link,
		})
	}
	if err != nil && ctx.Err() == nil {
		_, _ = fmt.Fprintf(w.errOut, "Could not show %q: %v\n", fields.Title, err)
	}
}

// fields describes a notification for the notifier, the line and the --exec command.
func (w *watcher) fields(n client.Notification) notify.Fields {
	f := notify.Fields{
		Title: notify.Clean(n.Title), Body: notify.CleanBody(n.Body), Severity: n.Severity, Kind: n.Kind,
		Category: n.Category, ID: n.ID, Session: n.SessionID, Execution: n.ExecutionID,
		Request: n.InputRequestID, Link: strings.TrimRight(w.c.BaseURL, "/") + "/inbox?item=" + n.ID,
	}
	if n.Kind == "ACTION" && n.ExecutionID != "" && n.InputRequestID != "" {
		f.Answer = "yukta inputs answer " + quote(n.ExecutionID) + " " + quote(n.InputRequestID) + " --decision ..."
	}
	return f
}

// text is the body of a desktop notification: the body, then the answer command of a question.
func text(f notify.Fields) string {
	parts := []string{}
	for _, part := range []string{f.Body, f.Answer} {
		if part != "" {
			parts = append(parts, part)
		}
	}
	return strings.Join(parts, "\n")
}

// line is one printed line.
func line(f notify.Fields) string {
	s := fmt.Sprintf("[%s] %s: %s", f.Severity, f.Category, f.Title)
	if f.Answer != "" {
		s += "  ->  " + f.Answer
	}
	return s
}

var plainWord = regexp.MustCompile(`^[A-Za-z0-9._:/@%+=-]+$`)

// quote makes an id safe to paste into a shell.
func quote(s string) string {
	if plainWord.MatchString(s) {
		return s
	}
	return "'" + strings.ReplaceAll(s, "'", `'\''`) + "'"
}
