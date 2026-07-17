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

// StatusCmd creates the "workflow status" command.
func StatusCmd(c client.ClientInterface) *cobra.Command {
	return &cobra.Command{
		Use:   "status <session-id> <execution-id>",
		Short: "Get the current status of a workflow execution",
		Long: `Get the current status and progress of a workflow execution.

Shows the execution ID, workflow ID, overall status, and progress of individual tasks.`,
		Args: cobra.ExactArgs(2),
		RunE: func(cmd *cobra.Command, args []string) error {
			sessionID := args[0]
			executionID := args[1]

			resp, err := c.GetWorkflowStatus(sessionID, executionID)
			if err != nil {
				return err
			}

			format := commands.GetOutputFormat()
			if format == "json" {
				output.PrintJSON(resp)
				return nil
			}

			fmt.Printf("Execution Status\n")
			fmt.Printf("  ID:       %s\n", resp.ExecutionID)
			fmt.Printf("  Workflow: %s\n", resp.WorkflowID)
			fmt.Printf("  Status:   %s\n", resp.Status)
			fmt.Printf("  Started:  %s\n", resp.StartTime)
			fmt.Printf("  Ended:    %s\n", resp.EndTime)
			fmt.Printf("  Tasks:    %d\n", len(resp.Tasks))
			return nil
		},
	}
}
