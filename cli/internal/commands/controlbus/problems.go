// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package controlbus

import (
	"fmt"
	"strings"

	"com.infenia.yukta/go-cli/internal/client"
)

const (
	// maxProblemLinesPerNode bounds what one node can print, so a node that fails with a screenful
	// of output costs a fixed number of lines.
	maxProblemLinesPerNode = 20
	maxProblemLineLength   = 300
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

// render turns an event into output lines: a log line becomes itself if the server rated it WARN or
// ERROR (or, for a HEAL report, itself), a status change stays itself followed by the problem lines
// the server rated in the reason it carries.
func (p *problemFilter) render(e client.ExecutionEvent) []string {
	if line, ok := healLine(e); ok {
		return []string{line}
	}
	if e.Type == client.EventLogLine {
		return p.logLine(e)
	}
	out := []string{formatEvent(e)}
	if found := p.reasonLines(e); len(found) > 0 {
		out = append(out, found...)
	} else if reason, _ := e.Metadata["error"].(string); strings.TrimSpace(reason) != "" {
		// a reason with no rated line ("Process timed out after 3600s") is still the reason
		first, _, _ := strings.Cut(strings.TrimSpace(reason), "\n")
		out = append(out, p.admit(e.NodeID, "ERROR", first)...)
	}
	return out
}

// logLine renders a log event. The server rates every line, so the CLI keeps no keywords: a line
// the server rated WARN or ERROR is printed, anything else (INFO, or no rating) is not.
func (p *problemFilter) logLine(e client.ExecutionEvent) []string {
	line := strings.TrimSpace(e.Line)
	if line == "" || (e.Severity != "WARN" && e.Severity != "ERROR") {
		return nil
	}
	return p.admit(e.NodeID, e.Severity, line)
}

// reasonLines admits the problem lines the server rated in a failed node's reason, which arrive as
// metadata "problems": a list of {level, line}.
func (p *problemFilter) reasonLines(e client.ExecutionEvent) []string {
	var out []string
	list, _ := e.Metadata["problems"].([]any)
	for _, item := range list {
		entry, _ := item.(map[string]any)
		level, _ := entry["level"].(string)
		line, _ := entry["line"].(string)
		if line != "" && (level == "WARN" || level == "ERROR") {
			out = append(out, p.admit(e.NodeID, level, line)...)
		}
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
