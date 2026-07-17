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

// StartCmd creates the "workflow start" command.
func StartCmd(c client.ClientInterface) *cobra.Command {
	return &cobra.Command{
		Use:   "start <session-id> <workflow-id>",
		Short: "Start a workflow execution",
		Long: `Start executing a workflow in a given session.

Returns the execution ID of the started workflow.`,
		Args: cobra.ExactArgs(2),
		RunE: func(cmd *cobra.Command, args []string) error {
			sessionID := args[0]
			workflowID := args[1]

			resp, err := c.StartWorkflow(sessionID, workflowID)
			if err != nil {
				return err
			}

			format := commands.GetOutputFormat()
			if format == "json" {
				output.PrintJSON(resp)
				return nil
			}

			fmt.Printf("Workflow started with execution ID: %s\n", resp.ExecutionID)
			return nil
		},
	}
}
