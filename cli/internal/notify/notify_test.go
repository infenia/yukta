// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package notify

import (
	"context"
	"errors"
	"reflect"
	"strings"
	"testing"
)

// nasty is text that would end an AppleScript string and run a command if it were part of the script.
const nasty = `x\" & do shell script "id`

type call struct {
	env  []string
	name string
	args []string
}

type recorder struct {
	calls []call
	out   map[string]string
	fail  map[string]bool
	// answer, when set, decides the output of a call (the help text differs from a click).
	answer func(name string, args []string) string
}

func (r *recorder) run(_ context.Context, env []string, name string, args ...string) (string, error) {
	r.calls = append(r.calls, call{env, name, args})
	if r.fail[name] {
		return "", errors.New("failed")
	}
	if r.answer != nil {
		return r.answer(name, args), nil
	}
	return r.out[name], nil
}

func has(names ...string) LookPath {
	return func(name string) (string, error) {
		for _, n := range names {
			if n == name {
				return "/bin/" + name, nil
			}
		}
		return "", errors.New("not found")
	}
}

func TestDetect(t *testing.T) {
	r := &recorder{}
	cases := []struct {
		goos string
		look LookPath
		want string
	}{
		{"linux", has("notify-send"), "notify-send"},
		{"darwin", has("osascript"), "osascript"},
		{"windows", has("powershell"), "powershell"},
		{"windows", has("pwsh"), "pwsh"},
		{"linux", has(), ""},
		{"darwin", has(), ""},
		{"windows", has(), ""},
		{"plan9", has("notify-send"), ""},
	}
	for _, tc := range cases {
		n := Detect(tc.goos, tc.look, r.run)
		switch {
		case tc.want == "" && n != nil:
			t.Errorf("%s: got %s, want none", tc.goos, n.Name())
		case tc.want != "" && (n == nil || n.Name() != tc.want):
			t.Errorf("%s: got %v, want %s", tc.goos, n, tc.want)
		}
	}
}

func TestLinux_passesTextAsArgumentsAfterTheOptions(t *testing.T) {
	r := &recorder{out: map[string]string{"notify-send": "no actions here"}}
	n := Detect("linux", has("notify-send"), r.run)

	if err := n.Notify(context.Background(), Item{Title: "--evil", Body: "a\x1bb; rm -rf /", Severity: "ERROR", Link: "http://x"}); err != nil {
		t.Fatal(err)
	}

	got := r.calls[len(r.calls)-1]
	want := []string{"--app-name=Yukta", "--urgency=critical", "--", "--evil", "a b; rm -rf /"}
	if got.name != "notify-send" || !reflect.DeepEqual(got.args, want) {
		t.Errorf("call = %+v, want args %v", got, want)
	}
}

func TestLinux_urgencyByLevel(t *testing.T) {
	for severity, want := range map[string]string{"ERROR": "critical", "WARN": "normal", "INFO": "low", "": "low"} {
		if got := urgency(severity); got != want {
			t.Errorf("urgency(%q) = %s, want %s", severity, got, want)
		}
	}
}

func TestLinux_aClickOnOpenRunsXdgOpenWhereActionsAreSupported(t *testing.T) {
	r := &recorder{answer: func(_ string, args []string) string {
		if args[0] == "--help" {
			return "--action=ACTION  usage"
		}
		return "open\n"
	}}
	n := Detect("linux", has("notify-send"), r.run)

	if err := n.Notify(context.Background(), Item{Title: "t", Body: "b", Link: "http://x/inbox"}); err != nil {
		t.Fatal(err)
	}

	last := r.calls[len(r.calls)-1]
	if last.name != "xdg-open" || !reflect.DeepEqual(last.args, []string{"http://x/inbox"}) {
		t.Errorf("last call = %+v", last)
	}
	send := r.calls[len(r.calls)-2]
	if !strings.Contains(strings.Join(send.args, " "), "--action=open=Open --wait") {
		t.Errorf("send args = %v", send.args)
	}
}

func TestLinux_noClickMeansNoOpenAndAFailureIsReported(t *testing.T) {
	r := &recorder{answer: func(_ string, args []string) string {
		if args[0] == "--help" {
			return "--action"
		}
		return "" // closed without a click
	}}
	n := Detect("linux", has("notify-send"), r.run)

	if err := n.Notify(context.Background(), Item{Title: "t", Link: "http://x"}); err != nil {
		t.Fatal(err)
	}
	if r.calls[len(r.calls)-1].name != "notify-send" {
		t.Errorf("xdg-open must not run without a click: %+v", r.calls)
	}

	r.fail = map[string]bool{"notify-send": true}
	if err := n.Notify(context.Background(), Item{Title: "t"}); err == nil {
		t.Error("a failing tool must be an error")
	}
}

func TestMacOS_textIsAScriptArgumentNeverPartOfTheScript(t *testing.T) {
	r := &recorder{}
	n := Detect("darwin", has("osascript"), r.run)

	if err := n.Notify(context.Background(), Item{Title: "say \"hi\"", Body: nasty}); err != nil {
		t.Fatal(err)
	}

	got := r.calls[0]
	if got.name != "osascript" || got.args[len(got.args)-3] != "--" ||
		got.args[len(got.args)-2] != "say \"hi\"" || got.args[len(got.args)-1] != nasty {
		t.Errorf("call = %+v", got)
	}
	for _, a := range got.args[:len(got.args)-3] {
		if strings.Contains(a, "do shell script") {
			t.Errorf("the script must not hold the text: %s", a)
		}
	}
}

func TestWindows_textIsAnEnvironmentVariableNeverPartOfTheScript(t *testing.T) {
	r := &recorder{}
	n := Detect("windows", has("powershell"), r.run)

	if err := n.Notify(context.Background(), Item{Title: "t'$(calc)", Body: "b"}); err != nil {
		t.Fatal(err)
	}

	got := r.calls[0]
	if got.name != "powershell" || got.args[0] != "-NoProfile" || !strings.Contains(got.args[len(got.args)-1], "ToastNotificationManager") {
		t.Errorf("call = %+v", got)
	}
	if strings.Contains(got.args[len(got.args)-1], "calc") {
		t.Error("the script must not hold the text")
	}
	if !reflect.DeepEqual(got.env, []string{"YUKTA_NOTIFY_TITLE=t'$(calc)", "YUKTA_NOTIFY_BODY=b"}) {
		t.Errorf("env = %v", got.env)
	}
}

func TestExecRunner_runsAProgramWithoutAShell(t *testing.T) {
	out, err := ExecRunner(context.Background(), []string{"YUKTA_X=1"}, "sh", "-c", "printf %s \"$YUKTA_X\"")
	if err != nil || out != "1" {
		t.Errorf("out = %q, err = %v", out, err)
	}
	if _, err := ExecRunner(context.Background(), nil, "yukta-no-such-program"); err == nil {
		t.Error("a missing program must fail")
	}
}

func TestCleanBodyKeepsLineBreaks(t *testing.T) {
	if got := CleanBody("a\x1bb\nc\td"); got != "a b\nc d" {
		t.Errorf("CleanBody = %q", got)
	}
}

func TestCleanAndShort(t *testing.T) {
	if got := Clean("a\x1b[31mb\nc"); got != "a [31mb c" {
		t.Errorf("Clean = %q", got)
	}
	if got := Short("abcdef", 4); got != "abc…" {
		t.Errorf("Short = %q", got)
	}
	if got := Short("abc", 4); got != "abc" {
		t.Errorf("Short = %q", got)
	}
}
