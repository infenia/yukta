// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package logs

import (
	"context"
	"errors"
	"fmt"
	"os"
	"os/signal"
	"syscall"

	"com.infenia.yukta/go-cli/internal/client"
	"github.com/spf13/cobra"
)

// LogsCmd creates and returns the "logs" command.
// It streams execution logs for a given session and execution ID.
// The command prints logs in real-time, one per line, as they arrive from the server.
// It supports graceful cancellation via Ctrl-C.
func LogsCmd(c *client.Client) *cobra.Command {
	var entries bool
	var node, stream, grep string
	tail, head, offset, limit, ctxLines := -1, -1, -1, -1, -1
	cmd := &cobra.Command{
		Use:   "logs <session-id> <execution-id>",
		Short: "Stream execution logs",
		Long: `Stream execution logs for a workflow execution in real-time.

The logs are displayed as they arrive from the server, one line per log entry.
The stream continues until the execution reaches a terminal state (completed, failed, cancelled, etc).

Use --entries to receive structured log entries as JSON, one per line.

With --node N the command reads part of what that node wrote instead of streaming: --tail N,
--head N, a page (--offset and --limit, at most 500 lines) or the lines matching --grep REGEX
(with --context N lines around each). Lines are printed as "<line number>: <text>"; a note on
stderr says when lines were left out and where the next page starts.

Press Ctrl-C to stop watching logs at any time.`,
		Args: cobra.ExactArgs(2),
		RunE: func(cmd *cobra.Command, args []string) error {
			sessionID := args[0]
			executionID := args[1]
			if node != "" {
				return printNodeOutput(c, executionID, node, client.NodeOutputQuery{
					Stream: stream, Tail: tail, Head: head, Offset: offset, Limit: limit, Grep: grep, Context: ctxLines,
				})
			}

			// Create a context that can be cancelled by signal handlers
			baseCtx := cmd.Context()
			if baseCtx == nil {
				baseCtx = context.Background()
			}

			ctx, stop := signal.NotifyContext(baseCtx, os.Interrupt, syscall.SIGTERM)
			defer stop()

			// Stream logs, printing each line as it arrives
			stream := c.StreamExecutionLogs
			if entries {
				stream = c.StreamExecutionLogEntries
			}
			err := stream(ctx, sessionID, executionID, func(line string) error {
				fmt.Println(line)
				return nil
			})

			// If context was cancelled (Ctrl-C), treat it as a clean exit
			if errors.Is(err, context.Canceled) {
				return nil
			}

			return err
		},
	}
	cmd.Flags().BoolVar(&entries, "entries", false, "Stream structured log entries as JSON")
	cmd.Flags().StringVar(&node, "node", "", "Read the output of this node instead of streaming")
	cmd.Flags().StringVar(&stream, "stream", "", "With --node: stdout, stderr or all (default all)")
	cmd.Flags().IntVar(&tail, "tail", -1, "With --node: the last N lines")
	cmd.Flags().IntVar(&head, "head", -1, "With --node: the first N lines")
	cmd.Flags().IntVar(&offset, "offset", -1, "With --node: first line of a page (0-based)")
	cmd.Flags().IntVar(&limit, "limit", -1, "With --node: page size (default 50, at most 500)")
	cmd.Flags().StringVar(&grep, "grep", "", "With --node: only lines matching this regular expression")
	cmd.Flags().IntVar(&ctxLines, "context", -1, "With --node: lines of context around each --grep match (at most 20)")
	return cmd
}

// printNodeOutput prints one slice of a node's output as "<n>: <text>" lines.
func printNodeOutput(c *client.Client, executionID, node string, q client.NodeOutputQuery) error {
	out, err := c.GetNodeOutput(executionID, node, q)
	if err != nil {
		return err
	}
	for _, l := range out.Lines {
		fmt.Printf("%d: %s\n", l.Number, l.Text)
	}
	if out.Truncated {
		next := ""
		if out.NextOffset != nil {
			next = fmt.Sprintf("; next page: --offset %d", *out.NextOffset)
		}
		fmt.Fprintf(os.Stderr, "... %d of %d selected lines shown (%d lines, %d bytes in all)%s\n",
			len(out.Lines), out.MatchedLines, out.TotalLines, out.TotalBytes, next)
	}
	return nil
}
