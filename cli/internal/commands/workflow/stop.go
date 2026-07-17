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

// StopCmd creates the "workflow stop" command.
func StopCmd(c client.ClientInterface) *cobra.Command {
	return &cobra.Command{
		Use:   "stop <session-id> <workflow-id>",
		Short: "Stop a workflow execution",
		Long: `Stop all active executions of a workflow in a given session.

Returns the list of execution IDs that were stopped.`,
		Args: cobra.ExactArgs(2),
		RunE: func(cmd *cobra.Command, args []string) error {
			sessionID := args[0]
			workflowID := args[1]

			resp, err := c.StopWorkflow(sessionID, workflowID)
			if err != nil {
				return err
			}

			format := commands.GetOutputFormat()
			if format == "json" {
				output.PrintJSON(resp)
				return nil
			}

			fmt.Printf("Stopped %d execution(s)\n", len(resp.ExecutionIDs))
			for _, id := range resp.ExecutionIDs {
				fmt.Printf("  - %s\n", id)
			}
			return nil
		},
	}
}
