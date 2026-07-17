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

// EnableCmd creates the "workflow node step enable" command.
func EnableCmd(c client.ClientInterface) *cobra.Command {
	return &cobra.Command{
		Use:   "enable <session-id> <execution-id> <node-id>",
		Short: "Enable step-through debugging for a node",
		Long: `Enable step-through debug mode for a specific node.

When enabled, the workflow execution will pause before executing this node,
allowing you to step through its execution one step at a time.`,
		Args: cobra.ExactArgs(3),
		RunE: func(cmd *cobra.Command, args []string) error {
			sessionID := args[0]
			executionID := args[1]
			nodeID := args[2]

			resp, err := c.EnableStepMode(sessionID, executionID, nodeID)
			if err != nil {
				return err
			}

			format := commands.GetOutputFormat()
			if format == "json" {
				output.PrintJSON(resp)
				return nil
			}

			fmt.Printf("Step mode enabled for node: %s\n", nodeID)
			return nil
		},
	}
}
