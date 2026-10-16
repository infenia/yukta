// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package controlbus

import (
	"fmt"
	"regexp"
	"strings"

	"com.infenia.yukta/go-cli/internal/client"
)

const (
	// maxProblemLinesPerNode bounds what one node can print, so a node that fails with a screenful
	// of output costs a fixed number of lines.
	maxProblemLinesPerNode = 20
	maxProblemLineLength   = 300
	// wentWrongContext is how many lines after gradle's "What went wrong" are the cause.
	wentWrongContext = 3
)

// The patterns are the server's EventSeverity ones, so a line the server calls an error is one here,
// plus gradle's report pointer, which picks the useful line out of a failed node's reason.
var (
	errorLine = regexp.MustCompile(`(?i)\b(error|failed|failure)\b|exception|What went wrong|See the report at`)
	warnLine  = regexp.MustCompile(`(?i)\bwarn(ing)?\b|deprecat`)
)

// problemFilter reduces events to the lines that say what went wrong:
//
//	ERROR <node>: <line>     WARN <node>: <line>
//
// A line is printed once per node and a node prints at most maxProblemLinesPerNode of them.
type problemFilter struct {
	seen  map[string]bool
	count map[string]int
}

func newProblemFilter() *problemFilter {
	return &problemFilter{seen: map[string]bool{}, count: map[string]int{}}
}

// isWarnOrError reports whether the server rated the event WARN or above; --quiet keeps such
// events next to isProblemOrEnd, which is how error and warning log lines get through.
func isWarnOrError(e client.ExecutionEvent) bool {
	return e.Severity == "WARN" || e.Severity == "ERROR"
}

// healPrefix starts the one-line report a self-healing fix step prints (yukta-heal.sh), which
// --quiet always shows: it is how a watcher learns that a failure was handled.
const healPrefix = "HEAL "

// healLine returns the report in a log line, if it is one.
func healLine(e client.ExecutionEvent) (string, bool) {
	if e.Type != client.EventLogLine {
		return "", false
	}
	line := strings.TrimSpace(e.Line)
	return line, strings.HasPrefix(line, healPrefix)
}

// render turns an event into output lines: a log line becomes its problem lines (or, for a HEAL
// report, itself), a status change stays itself followed by the problem lines of the reason it
// carries.
func (p *problemFilter) render(e client.ExecutionEvent) []string {
	if line, ok := healLine(e); ok {
		return []string{line}
	}
	if e.Type == client.EventLogLine {
		return p.lines(e.NodeID, e.Line)
	}
	out := []string{formatEvent(e)}
	if reason, _ := e.Metadata["error"].(string); strings.TrimSpace(reason) != "" {
		found := p.lines(e.NodeID, reason)
		if len(found) == 0 {
			// a reason with no keyword ("Process timed out after 3600s") is still the reason
			first, _, _ := strings.Cut(strings.TrimSpace(reason), "\n")
			found = p.admit(e.NodeID, "ERROR", first)
		}
		out = append(out, found...)
	}
	return out
}

// lines picks the problem lines out of text: error and warning lines, and the lines that follow
// gradle's "What went wrong".
func (p *problemFilter) lines(node, text string) []string {
	var out []string
	context := 0
	for _, raw := range strings.Split(text, "\n") {
		line := strings.TrimSpace(raw)
		if line == "" {
			continue
		}
		level := "ERROR"
		switch {
		case strings.Contains(line, "What went wrong"):
			context = wentWrongContext
		case context > 0 && strings.HasPrefix(line, "* "):
			// the next gradle section ("* Try:") ends the cause
			context = 0
			continue
		case context > 0:
			context--
		case errorLine.MatchString(line):
		case warnLine.MatchString(line):
			level = "WARN"
		default:
			continue
		}
		out = append(out, p.admit(node, level, line)...)
	}
	return out
}

func (p *problemFilter) admit(node, level, line string) []string {
	if len(line) > maxProblemLineLength {
		line = line[:maxProblemLineLength] + "…"
	}
	key := node + "\x00" + line
	if p.seen[key] {
		return nil
	}
	p.seen[key] = true
	n := p.count[node]
	if n > maxProblemLinesPerNode {
		return nil
	}
	p.count[node]++
	if n == maxProblemLinesPerNode {
		return []string{fmt.Sprintf("%s %s: … more problem lines suppressed (see the report)", level, node)}
	}
	return []string{fmt.Sprintf("%s %s: %s", level, node, line)}
}
