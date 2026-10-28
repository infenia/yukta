// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package controlbus

import (
	"fmt"
	"reflect"
	"strings"
	"testing"

	"com.infenia.yukta/go-cli/internal/client"
	"com.infenia.yukta/go-cli/internal/commands"
	"com.infenia.yukta/go-cli/internal/testutil"
)

const gradleFailure = "Process failed with exit code 1\n--- Output ---\n> Task :checkstyleMain\n" +
	"[ant:checkstyle] [ERROR] Foo.java:3: bad\n\n* What went wrong:\n" +
	"Execution failed for task ':checkstyleMain'.\n> Checkstyle rule violations were found.\n" +
	"See the report at: file:///x/index.html\n* Try:\n> Run with --stacktrace"

func nodeFailure(node, reason string, problems ...map[string]any) client.ExecutionEvent {
	metadata := map[string]any{"error": reason}
	if len(problems) > 0 {
		list := make([]any, len(problems))
		for i, p := range problems {
			list[i] = p
		}
		metadata["problems"] = list
	}
	return client.ExecutionEvent{
		Type: client.EventNodeStatusChanged, NodeID: node, Status: "FAILURE", Severity: "ERROR",
		Metadata: metadata,
	}
}

func problem(level, line string) map[string]any {
	return map[string]any{"level": level, "line": line}
}

func TestProblemFilter_reducesAFailureToTheLinesTheServerRated(t *testing.T) {
	got := newProblemFilter().render(nodeFailure("style", gradleFailure,
		problem("ERROR", "Process failed with exit code 1"),
		problem("ERROR", "* What went wrong:"),
		problem("ERROR", "See the report at: file:///x/index.html")))

	want := []string{
		"NODE style FAILURE",
		"ERROR style: Process failed with exit code 1",
		"ERROR style: * What went wrong:",
		"ERROR style: See the report at: file:///x/index.html",
	}
	if !reflect.DeepEqual(got, want) {
		t.Errorf("got:\n%s\nwant:\n%s", strings.Join(got, "\n"), strings.Join(want, "\n"))
	}
}

func TestProblemFilter_reasonWithoutRatedLinesKeepsItsFirstLine(t *testing.T) {
	got := newProblemFilter().render(nodeFailure("tests", "Process timed out after 3600s\nmore"))

	want := []string{"NODE tests FAILURE", "ERROR tests: Process timed out after 3600s"}
	if !reflect.DeepEqual(got, want) {
		t.Errorf("got %q, want %q", got, want)
	}
}

func TestProblemFilter_aReasonLineWithoutALevelOrTextIsIgnored(t *testing.T) {
	got := newProblemFilter().render(nodeFailure("n", "boom\nmore",
		problem("INFO", "fine"), problem("ERROR", ""), problem("", "no level")))

	// nothing usable among the rated lines, so the reason's first line stands in
	want := []string{"NODE n FAILURE", "ERROR n: boom"}
	if !reflect.DeepEqual(got, want) {
		t.Errorf("got %q, want %q", got, want)
	}
}

func TestProblemFilter_statusWithoutReasonIsJustTheStatus(t *testing.T) {
	cancelled := client.ExecutionEvent{Type: client.EventNodeStatusChanged, NodeID: "n", Status: "CANCELLED"}
	blank := nodeFailure("n", "  \n")
	f := newProblemFilter()

	if got := f.render(cancelled); !reflect.DeepEqual(got, []string{"NODE n CANCELLED"}) {
		t.Errorf("cancelled: %q", got)
	}
	if got := f.render(blank); !reflect.DeepEqual(got, []string{"NODE n FAILURE"}) {
		t.Errorf("blank reason: %q", got)
	}
}

func TestProblemFilter_logLinesKeepOnlyProblemsAndNeverRepeat(t *testing.T) {
	f := newProblemFilter()
	log := client.ExecutionEvent{
		Type: client.EventLogLine, NodeID: "tests", Severity: "WARN",
		Line: "warning: [deprecation] foo()",
	}

	if got := f.render(log); !reflect.DeepEqual(got, []string{"WARN tests: warning: [deprecation] foo()"}) {
		t.Errorf("first: %q", got)
	}
	if got := f.render(log); len(got) != 0 {
		t.Errorf("repeat: %q", got)
	}
}

// The server rates every line, so the CLI takes that rating and has no keywords of its own: a line
// the server calls an error is one even without them, and one it calls INFO is not.
func TestProblemFilter_logEventTakesTheServersRating(t *testing.T) {
	f := newProblemFilter()
	rated := func(severity, line string) client.ExecutionEvent {
		return client.ExecutionEvent{Type: client.EventLogLine, NodeID: "n", Severity: severity, Line: line}
	}

	if got := f.render(rated("ERROR", "the build broke")); !reflect.DeepEqual(got, []string{"ERROR n: the build broke"}) {
		t.Errorf("error: %q", got)
	}
	if got := f.render(rated("WARN", "careful now")); !reflect.DeepEqual(got, []string{"WARN n: careful now"}) {
		t.Errorf("warn: %q", got)
	}
	if got := f.render(rated("INFO", "error budget is fine")); len(got) != 0 {
		t.Errorf("info is not a problem even with a keyword in it: %q", got)
	}
	if got := f.render(rated("", "error: boom")); len(got) != 0 {
		t.Errorf("no rating is INFO, whatever the text says: %q", got)
	}
	if got := f.render(rated("ERROR", "   ")); len(got) != 0 {
		t.Errorf("a blank line says nothing: %q", got)
	}
}

func TestProblemFilter_capsALongLineAndANoisyNode(t *testing.T) {
	f := newProblemFilter()

	long := f.admit("n", "ERROR", "error: "+strings.Repeat("x", 1000))
	if len(long) != 1 || !strings.HasSuffix(long[0], "…") || len(long[0]) > maxProblemLineLength+20 {
		t.Errorf("long line: %q", long)
	}

	var got []string
	for i := 0; i < maxProblemLinesPerNode+5; i++ {
		got = append(got, f.admit("n", "ERROR", fmt.Sprintf("error number %d", i))...)
	}
	// one line is already used by the long one: the cap leaves room for the rest, then one notice
	if len(got) != maxProblemLinesPerNode || !strings.Contains(got[len(got)-1], "suppressed") {
		t.Errorf("got %d lines, last %q", len(got), got[len(got)-1])
	}
	if more := f.admit("n", "ERROR", "error: yet another"); len(more) != 0 {
		t.Errorf("after the cap: %q", more)
	}
}

func TestProblemFilter_warningsDoNotUseUpTheRoomOfTheErrorThatFailedTheNode(t *testing.T) {
	f := newProblemFilter()

	var warns []string
	for i := 0; i < maxProblemLinesPerNode+5; i++ {
		warns = append(warns, f.admit("n", "WARN", fmt.Sprintf("warning: number %d", i))...)
	}
	if len(warns) != maxWarnLinesPerNode+1 || !strings.Contains(warns[len(warns)-1], "suppressed") {
		t.Errorf("got %d warn lines, last %q", len(warns), warns[len(warns)-1])
	}

	got := f.admit("n", "ERROR", "Foo.java:27: error: cannot be applied")
	if want := []string{"ERROR n: Foo.java:27: error: cannot be applied"}; !reflect.DeepEqual(got, want) {
		t.Errorf("the error that failed the node: got %q, want %q", got, want)
	}
}

func TestIsWarnOrError(t *testing.T) {
	cases := map[string]struct {
		event client.ExecutionEvent
		want  bool
	}{
		"info node":     {client.ExecutionEvent{Type: client.EventNodeStatusChanged, Severity: "INFO"}, false},
		"warn control":  {client.ExecutionEvent{Type: client.EventControlOutcome, Severity: "WARN"}, true},
		"error node":    {client.ExecutionEvent{Type: client.EventNodeStatusChanged, Severity: "ERROR"}, true},
		"workflow end":  {client.ExecutionEvent{Type: client.EventWorkflowStatusChanged}, false},
		"unrated event": {client.ExecutionEvent{Type: client.EventLogLine}, false},
	}
	for name, c := range cases {
		if got := isWarnOrError(c.event); got != c.want {
			t.Errorf("%s: got %v, want %v", name, got, c.want)
		}
	}
}

const problemsStream = "data: " + `{"type":"NodeStatusChanged","severity":"INFO","event":{"executionId":"e","nodeId":"spotless","status":"RUNNING"}}` + "\n\n" +
	"data: " + `{"type":"LogLine","severity":"INFO","event":{"executionId":"e","nodeId":"spotless","line":"all good"}}` + "\n\n" +
	"data: " + `{"type":"LogLine","severity":"WARN","event":{"executionId":"e","nodeId":"tests","line":"warning: [deprecation] foo()"}}` + "\n\n" +
	"data: " + `{"type":"NodeStatusChanged","severity":"ERROR","event":{"executionId":"e","nodeId":"style","status":"FAILURE","metadata":{"error":"Process failed with exit code 1\n* What went wrong:\nExecution failed.\nSee the report at: file:///r","problems":[{"level":"ERROR","line":"Process failed with exit code 1"},{"level":"ERROR","line":"* What went wrong:"},{"level":"ERROR","line":"Execution failed."},{"level":"ERROR","line":"See the report at: file:///r"}]}}}` + "\n\n" +
	"data: " + `{"type":"ControlOutcome","severity":"WARN","event":{"executionId":"e","command":"Pause","applied":false,"reason":"denied"}}` + "\n\n" +
	"data: " + `{"type":"WorkflowStatusChanged","event":{"executionId":"e","status":"FAILURE"}}` + "\n\n"

func TestWatchCmd_quietShowsProblemsWithTheirCauses(t *testing.T) {
	out, err := testutil.Run(t, WatchCmd(testutil.Stream(t, problemsStream)), "--quiet")
	if err != nil && !feedEnded(err) {
		t.Fatal(err)
	}
	want := "WARN tests: warning: [deprecation] foo()\n" +
		"NODE style FAILURE\n" +
		"ERROR style: Process failed with exit code 1\n" +
		"ERROR style: * What went wrong:\n" +
		"ERROR style: Execution failed.\n" +
		"ERROR style: See the report at: file:///r\n" +
		"CONTROL Pause applied=false denied\n" +
		"WORKFLOW FAILURE\n"
	if out != want {
		t.Errorf("output:\n%s\nwant:\n%s", out, want)
	}
}

func TestWatchCmd_quietWithJsonKeepsTheEventsAsTheyAre(t *testing.T) {
	commands.SetTestOutputFormat("json")
	defer commands.SetTestOutputFormat("table")

	out, err := testutil.Run(t, WatchCmd(testutil.Stream(t, problemsStream)), "--quiet")
	if err != nil && !feedEnded(err) {
		t.Fatal(err)
	}
	lines := strings.Split(strings.TrimSpace(out), "\n")
	if len(lines) != 4 || !strings.Contains(lines[1], `"severity":"ERROR"`) ||
		!strings.Contains(lines[1], "What went wrong") {
		t.Errorf("output %q", out)
	}
}

func TestWatchCmd_quietAlwaysShowsAHealReport(t *testing.T) {
	stream := "data: " + `{"type":"LogLine","severity":"INFO","event":{"executionId":"e","nodeId":"style-fix1","line":"  HEAL style attempt=1 mode=fresh fixed=true files=2 cost=$0.21 summary=removed the unused import"}}` + "\n\n" +
		"data: " + `{"type":"LogLine","severity":"INFO","event":{"executionId":"e","nodeId":"style-fix1","line":"ordinary output"}}` + "\n\n" +
		"data: " + `{"type":"WorkflowStatusChanged","event":{"executionId":"e","status":"SUCCESS"}}` + "\n\n"

	out, err := testutil.Run(t, WatchCmd(testutil.Stream(t, stream)), "--quiet")
	if err != nil && !feedEnded(err) {
		t.Fatal(err)
	}
	want := "HEAL style attempt=1 mode=fresh fixed=true files=2 cost=$0.21 summary=removed the unused import\n" +
		"WORKFLOW SUCCESS\n"
	if out != want {
		t.Errorf("output:\n%s\nwant:\n%s", out, want)
	}
}

func TestHealLine(t *testing.T) {
	if _, ok := healLine(client.ExecutionEvent{Type: client.EventNodeStatusChanged, Line: "HEAL x"}); ok {
		t.Error("a status event is not a heal report")
	}
	if _, ok := healLine(client.ExecutionEvent{Type: client.EventLogLine, Line: "no heal here"}); ok {
		t.Error("an ordinary log line is not a heal report")
	}
}
