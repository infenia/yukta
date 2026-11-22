// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

// Package digest holds the "digest" command: why a run failed, in a few lines.
package digest

import (
	"fmt"

	"com.infenia.yukta/go-cli/internal/client"
	"com.infenia.yukta/go-cli/internal/commands"
	"com.infenia.yukta/go-cli/internal/output"
	"github.com/spf13/cobra"
)

// DigestCmd creates the "digest" command.
func DigestCmd(c *client.Client) *cobra.Command {
	return &cobra.Command{
		Use:   "digest <execution-id>",
		Short: "Show why a run failed, in a few lines",
		Long: `Show why a run failed: its status, the nodes that failed or timed out (also in failed
sub-runs) with their error, the failures recognised in their output (failed tests, compile errors,
lint violations, failed tasks, with file:line) and the commands that show more.

Read this before any log: it is a few kilobytes where a log can be megabytes. Then follow the
"next" commands (logs --node with an offset, or logs search).`,
		Args: cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			d, err := c.GetDigest(args[0])
			if err != nil {
				return fmt.Errorf("failed to get the digest: %w", err)
			}
			if commands.GetOutputFormat() == "json" {
				output.PrintJSON(d)
				return nil
			}
			printDigest(d)
			return nil
		},
	}
}

func printDigest(d client.FailureDigest) {
	state := "ended"
	if !d.Complete {
		state = "still running"
	}
	fmt.Printf("run %s (%s): %s, %s, %d run(s) searched\n", d.ExecutionID, d.WorkflowID, d.Status, state, d.RunsSearched)
	for _, n := range d.FailedNodes {
		reason := ""
		if n.Error != "" {
			reason = ": " + n.Error
		}
		fmt.Printf("failed: %s [%s] %s%s (%d lines, %d errors, run %s)\n",
			n.NodeID, n.Module, n.Status, reason, n.Lines, n.ErrorLines, n.ExecutionID)
	}
	if len(d.Findings) > 0 {
		rows := make([][]string, 0, len(d.Findings))
		for _, f := range d.Findings {
			rows = append(rows, []string{f.Kind, dash(f.Name), dash(f.Location), f.Message, fmt.Sprintf("%s:%d", f.NodeID, f.Line)})
		}
		output.PrintTable([]string{"Kind", "Name", "Location", "Message", "Node:line"}, rows)
	}
	if d.FindingsOmitted > 0 {
		fmt.Printf("(%d more findings left out)\n", d.FindingsOmitted)
	}
	for _, r := range d.Next {
		fmt.Printf("next: %s   # %s\n", command(r), r.Why)
	}
}

// command is the CLI command that makes a read the digest suggests.
func command(r client.DigestRead) string {
	if r.Kind == "NODE_OUTPUT" {
		offset := int64(0)
		if r.Offset != nil {
			offset = *r.Offset
		}
		return fmt.Sprintf("yukta-cli logs <session-id> %s --node %s --offset %d --limit 40", r.ExecutionID, r.NodeID, offset)
	}
	nodes := ""
	if r.NodeID != "" {
		nodes = " --nodes " + r.NodeID
	}
	return fmt.Sprintf("yukta-cli logs search %s%s --level %s --mode LINES", r.ExecutionID, nodes, r.Level)
}

func dash(s string) string {
	if s == "" {
		return "-"
	}
	return s
}
