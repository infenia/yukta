// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package logs

import (
	"fmt"
	"os"
	"strings"

	"com.infenia.yukta/go-cli/internal/client"
	"com.infenia.yukta/go-cli/internal/commands"
	"com.infenia.yukta/go-cli/internal/output"
	"github.com/spf13/cobra"
)

// SearchCmd creates the "logs search" command: a grep over a run's logs, counts first.
func SearchCmd(c *client.Client) *cobra.Command {
	var q client.LogSearchQuery
	cmd := &cobra.Command{
		Use:   "search <execution-id>",
		Short: "Search a run's logs like grep, counts first",
		Long: `Search the logs of a run (and its sub-runs with --children) without reading them whole.

--mode COUNT (default) prints lines, errors, warnings and matches per node; NODES only the nodes
that matched; LINES the matching lines with --context lines from the same node, at most
--max (default 50) and 16 KB. Narrow with --nodes, --stream, --level and --grep. A cut answer
prints the --cursor for the next page and a hint for the next search on stderr.`,
		Args: cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			out, err := c.SearchLogs(args[0], q)
			if err != nil {
				return fmt.Errorf("failed to search the logs: %w", err)
			}
			if commands.GetOutputFormat() == "json" {
				output.PrintJSON(out)
				return nil
			}
			printSearch(out)
			return nil
		},
	}
	cmd.Flags().BoolVar(&q.IncludeChildren, "children", false, "Also search the runs it started")
	cmd.Flags().StringVar(&q.Nodes, "nodes", "", "Comma-separated node ids (default all)")
	cmd.Flags().StringVar(&q.Stream, "stream", "", "stdout, stderr or all (default all)")
	cmd.Flags().StringVar(&q.Level, "level", "", "Lowest level: ERROR, WARN or INFO")
	cmd.Flags().StringVar(&q.Grep, "grep", "", "Regular expression a line must contain")
	cmd.Flags().IntVar(&q.Context, "context", 0, "Lines of the same node around a match (LINES, at most 20)")
	cmd.Flags().StringVar(&q.Mode, "mode", "", "COUNT (default), NODES or LINES")
	cmd.Flags().IntVar(&q.MaxMatches, "max", 0, "Most matching lines (LINES, default 50, at most 500)")
	cmd.Flags().StringVar(&q.Cursor, "cursor", "", "The cursor a cut answer printed, for the next page")
	return cmd
}

func printSearch(out client.LogSearch) {
	if strings.EqualFold(out.Mode, "LINES") {
		for _, l := range out.Lines {
			marker := " "
			if l.Match {
				marker = ">"
			}
			fmt.Printf("%s %s#%d [%s] %s\n", marker, l.NodeID, l.Sequence, l.Level, l.Text)
		}
	} else {
		rows := make([][]string, 0, len(out.Nodes))
		for _, n := range out.Nodes {
			rows = append(rows, []string{n.NodeID, n.ExecutionID, fmt.Sprint(n.Lines), fmt.Sprint(n.Errors),
				fmt.Sprint(n.Warnings), fmt.Sprint(n.Matches)})
		}
		output.PrintTable([]string{"Node", "Run", "Lines", "Errors", "Warnings", "Matches"}, rows)
	}
	fmt.Fprintf(os.Stderr, "%d matches in %d lines of %d run(s)\n", out.Matches, out.LinesScanned, out.ExecutionsScanned)
	if out.NodesOmitted > 0 {
		fmt.Fprintf(os.Stderr, "(%d more nodes left out)\n", out.NodesOmitted)
	}
	if out.NextCursor != "" {
		fmt.Fprintf(os.Stderr, "next page: --cursor %s\n", out.NextCursor)
	}
	if out.Hint != "" {
		fmt.Fprintf(os.Stderr, "hint: %s\n", out.Hint)
	}
}
