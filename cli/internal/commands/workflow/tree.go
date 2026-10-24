// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package workflow

import (
	"fmt"

	"com.infenia.yukta/go-cli/internal/client"
	"com.infenia.yukta/go-cli/internal/commands"
	"com.infenia.yukta/go-cli/internal/output"
	"github.com/spf13/cobra"
)

// TreeCmd creates the "workflow tree" command.
func TreeCmd(c *client.Client) *cobra.Command {
	return &cobra.Command{
		Use:   "tree <execution-id>",
		Short: "Show an execution and the runs it started",
		Long: `Show an execution and every run it started as a sub-workflow, at any depth, oldest
first: one line per run with its workflow, status and parent.`,
		Args: cobra.ExactArgs(1),
		RunE: func(_ *cobra.Command, args []string) error {
			runs, err := c.GetExecutionTree(args[0])
			if err != nil {
				return err
			}
			if commands.GetOutputFormat() == "json" {
				output.PrintJSON(runs)
				return nil
			}
			for _, run := range runs {
				parent := run.ParentExecutionID
				if parent == "" {
					parent = "-"
				}
				fmt.Printf("%s %s %s parent=%s\n", run.ExecutionID, run.WorkflowID, run.Status, parent)
			}
			return nil
		},
	}
}
