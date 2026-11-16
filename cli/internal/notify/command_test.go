// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package notify

import (
	"context"
	"reflect"
	"testing"
)

func TestSplitCommand(t *testing.T) {
	cases := map[string][]string{
		"tmux display-message {{.Title}}": {"tmux", "display-message", "{{.Title}}"},
		`say "hello world" 'a b' c\ d`:    {"say", "hello world", "a b", "c d"},
		`echo "a \"quoted\" word"`:        {"echo", `a "quoted" word`},
		`echo 'no \escape'`:               {"echo", `no \escape`},
		"  spaced   out\t":                {"spaced", "out"},
		`echo ""`:                         {"echo", ""},
	}
	for line, want := range cases {
		got, err := SplitCommand(line)
		if err != nil || !reflect.DeepEqual(got, want) {
			t.Errorf("SplitCommand(%q) = %q, %v; want %q", line, got, err, want)
		}
	}
	for _, bad := range []string{"", "   ", `echo "open`, `echo 'open`, `echo trailing\`} {
		if _, err := SplitCommand(bad); err == nil {
			t.Errorf("SplitCommand(%q) must fail", bad)
		}
	}
}

func TestCommand_expandsEachWordAfterTheSplitSoTextStaysOneArgument(t *testing.T) {
	command, err := ParseCommand("notifier --title {{.Title}} --id={{.ID}}")
	if err != nil {
		t.Fatal(err)
	}
	r := &recorder{}

	err = command.Run(context.Background(), r.run, Fields{Title: "two words; $(id) 'x'", ID: "n1"})

	if err != nil {
		t.Fatal(err)
	}
	got := r.calls[0]
	if got.name != "notifier" || !reflect.DeepEqual(got.args, []string{"--title", "two words; $(id) 'x'", "--id=n1"}) {
		t.Errorf("call = %+v", got)
	}
}

func TestCommand_errors(t *testing.T) {
	if _, err := ParseCommand(`echo "open`); err == nil {
		t.Error("an unfinished quote must fail")
	}
	if _, err := ParseCommand("echo {{.Title"); err == nil {
		t.Error("a bad template must fail")
	}
	command, _ := ParseCommand("echo {{.Nope}}")
	if err := command.Run(context.Background(), (&recorder{}).run, Fields{}); err == nil {
		t.Error("an unknown field must fail")
	}
	ok, _ := ParseCommand("echo hi")
	r := &recorder{fail: map[string]bool{"echo": true}}
	if err := ok.Run(context.Background(), r.run, Fields{}); err == nil {
		t.Error("a failing command must be an error")
	}
}
