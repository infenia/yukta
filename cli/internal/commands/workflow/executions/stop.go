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

// StopCmd creates the "workflow executions stop" command.
func StopCmd(c client.ClientInterface) *cobra.Command {
	return &cobra.Command{
		Use:   "stop <execution-id>",
		Short: "Stop a workflow execution",
		Long: `Stop a specific workflow execution by its execution ID.

Returns the execution ID of the stopped execution.`,
		Args: cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			executionID := args[0]

			resp, err := c.StopExecution(executionID)
			if err != nil {
				return err
			}

			format := commands.GetOutputFormat()
			if format == "json" {
				output.PrintJSON(resp)
				return nil
			}

			fmt.Printf("Execution stopped: %s\n", resp.ExecutionID)
			return nil
		},
	}
}
