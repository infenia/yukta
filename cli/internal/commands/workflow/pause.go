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

// PauseCmd creates the "workflow pause" command.
func PauseCmd(c client.ClientInterface) *cobra.Command {
	return &cobra.Command{
		Use:   "pause <session-id> <execution-id>",
		Short: "Pause a workflow execution",
		Long: `Pause an active workflow execution.

Returns the execution ID of the paused workflow.`,
		Args: cobra.ExactArgs(2),
		RunE: func(cmd *cobra.Command, args []string) error {
			sessionID := args[0]
			executionID := args[1]

			resp, err := c.PauseWorkflow(sessionID, executionID)
			if err != nil {
				return err
			}

			format := commands.GetOutputFormat()
			if format == "json" {
				output.PrintJSON(resp)
				return nil
			}

			fmt.Printf("Execution paused: %s\n", resp.ExecutionID)
			return nil
		},
	}
}
