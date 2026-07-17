// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package executions

import (
	"fmt"

	"com.infenia.yukta/go-cli/internal/client"
	"com.infenia.yukta/go-cli/internal/commands"
	"com.infenia.yukta/go-cli/internal/output"
	"github.com/spf13/cobra"
)

// RestartCmd creates the "workflow executions restart" command.
func RestartCmd(c client.ClientInterface) *cobra.Command {
	return &cobra.Command{
		Use:   "restart <execution-id> [from-node-id]",
		Short: "Restart a workflow execution",
		Long: `Restart a workflow execution, optionally from a specific node.

If from-node-id is provided, the execution will restart from that node.
Otherwise, it will restart from the beginning.

Returns the execution ID of the new execution.`,
		Args: cobra.RangeArgs(1, 2),
		RunE: func(cmd *cobra.Command, args []string) error {
			executionID := args[0]
			var resp client.WorkflowStartResponse
			var err error

			if len(args) == 2 {
				fromNodeID := args[1]
				resp, err = c.RestartFromNode(executionID, fromNodeID)
			} else {
				resp, err = c.RestartExecution(executionID)
			}

			if err != nil {
				return err
			}

			format := commands.GetOutputFormat()
			if format == "json" {
				output.PrintJSON(resp)
				return nil
			}

			fmt.Printf("Execution restarted with ID: %s\n", resp.ExecutionID)
			return nil
		},
	}
}
