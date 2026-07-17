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

// ResumeCmd creates the "workflow resume" command.
func ResumeCmd(c client.ClientInterface) *cobra.Command {
	return &cobra.Command{
		Use:   "resume <session-id> <execution-id>",
		Short: "Resume a paused workflow execution",
		Long: `Resume a paused workflow execution.

Returns the execution ID of the resumed workflow.`,
		Args: cobra.ExactArgs(2),
		RunE: func(cmd *cobra.Command, args []string) error {
			sessionID := args[0]
			executionID := args[1]

			resp, err := c.ResumeWorkflow(sessionID, executionID)
			if err != nil {
				return err
			}

			format := commands.GetOutputFormat()
			if format == "json" {
				output.PrintJSON(resp)
				return nil
			}

			fmt.Printf("Execution resumed: %s\n", resp.ExecutionID)
			return nil
		},
	}
}
