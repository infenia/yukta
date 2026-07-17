// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package step

import (
	"fmt"

	"com.infenia.yukta/go-cli/internal/client"
	"com.infenia.yukta/go-cli/internal/commands"
	"com.infenia.yukta/go-cli/internal/output"
	"github.com/spf13/cobra"
)

// DisableCmd creates the "workflow node step disable" command.
func DisableCmd(c client.ClientInterface) *cobra.Command {
	return &cobra.Command{
		Use:   "disable <session-id> <execution-id> <node-id>",
		Short: "Disable step-through debugging for a node",
		Long: `Disable step-through debug mode for a specific node.

The workflow execution will resume normal execution for this node.`,
		Args: cobra.ExactArgs(3),
		RunE: func(cmd *cobra.Command, args []string) error {
			sessionID := args[0]
			executionID := args[1]
			nodeID := args[2]

			resp, err := c.DisableStepMode(sessionID, executionID, nodeID)
			if err != nil {
				return err
			}

			format := commands.GetOutputFormat()
			if format == "json" {
				output.PrintJSON(resp)
				return nil
			}

			fmt.Printf("Step mode disabled for node: %s\n", nodeID)
			return nil
		},
	}
}
