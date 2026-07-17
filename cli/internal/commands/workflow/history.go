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

// HistoryCmd creates the "workflow history" command.
func HistoryCmd(c client.ClientInterface) *cobra.Command {
	return &cobra.Command{
		Use:   "history <session-id>",
		Short: "Get the execution history of a session",
		Long: `Get the list of all workflow executions in a session,
including their status and timestamps.`,
		Args: cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			sessionID := args[0]

			summaries, err := c.GetWorkflowHistory(sessionID)
			if err != nil {
				return err
			}

			format := commands.GetOutputFormat()
			if format == "json" {
				output.PrintJSON(summaries)
				return nil
			}

			fmt.Printf("Execution History for Session: %s\n", sessionID)
			fmt.Printf("Total Executions: %d\n\n", len(summaries))
			for _, s := range summaries {
				fmt.Printf("  Execution ID: %s\n", s.ExecutionID)
				fmt.Printf("    Workflow:  %s\n", s.WorkflowID)
				fmt.Printf("    Status:    %s\n", s.Status)
				fmt.Printf("    Started:   %s\n", s.StartTime)
				fmt.Printf("    Ended:     %s\n\n", s.EndTime)
			}
			return nil
		},
	}
}
