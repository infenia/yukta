package controlbus

import (
	"fmt"
	"strings"
	"testing"
)

// Grading test for issue #1207, applied after a run and never shown to either arm. It checks the
// behaviour the issue asks for, through problemFilter.admit (the function the issue names).

func hiddenAdmitAll(f *problemFilter, node, level string, n int, text string) []string {
	var out []string
	for i := 0; i < n; i++ {
		out = append(out, f.admit(node, level, fmt.Sprintf("%s %d", text, i))...)
	}
	return out
}

func TestHidden_errorSurvivesAWarnFlood(t *testing.T) {
	f := newProblemFilter()
	out := hiddenAdmitAll(f, "gradle", "WARN", 30, "warning: [OperatorPrecedence] case")
	out = append(out, f.admit("gradle", "ERROR", "ResultCollectorTest.java:27: error: method containsExactly cannot be applied")...)
	out = append(out, f.admit("gradle", "ERROR", "1 error")...)
	joined := strings.Join(out, "\n")
	for _, want := range []string{
		"ERROR gradle: ResultCollectorTest.java:27: error: method containsExactly cannot be applied",
		"ERROR gradle: 1 error",
	} {
		if !strings.Contains(joined, want) {
			t.Fatalf("the ERROR line was crowded out by WARN lines: missing %q in\n%s", want, joined)
		}
	}
	if len(out) > 40 {
		t.Fatalf("a node's output is no longer bounded: %d lines", len(out))
	}
}

func TestHidden_aFloodOfOneLevelStaysBounded(t *testing.T) {
	for _, level := range []string{"WARN", "ERROR"} {
		out := hiddenAdmitAll(newProblemFilter(), "n", level, 200, "problem")
		if len(out) > 45 {
			t.Fatalf("%s flood printed %d lines", level, len(out))
		}
		if len(out) < 10 {
			t.Fatalf("%s flood printed only %d lines: the cap is too tight to be useful", level, len(out))
		}
	}
}

func TestHidden_nodesDoNotShareABudget(t *testing.T) {
	f := newProblemFilter()
	hiddenAdmitAll(f, "a", "WARN", 100, "noise")
	got := f.admit("b", "ERROR", "boom")
	if len(got) != 1 || got[0] != "ERROR b: boom" {
		t.Fatalf("node b was affected by node a: %v", got)
	}
}

func TestHidden_repeatedLinesStillPrintOnce(t *testing.T) {
	f := newProblemFilter()
	if got := f.admit("n", "ERROR", "same"); len(got) != 1 {
		t.Fatalf("first: %v", got)
	}
	if got := f.admit("n", "ERROR", "same"); len(got) != 0 {
		t.Fatalf("repeat: %v", got)
	}
}
