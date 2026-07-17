// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package node

import (
	"fmt"

	"com.infenia.yukta/go-cli/internal/client"
	"com.infenia.yukta/go-cli/internal/commands"
	"com.infenia.yukta/go-cli/internal/output"
	"github.com/spf13/cobra"
)

// PauseCmd creates the "workflow node pause" command.
func PauseCmd(c client.ClientInterface) *cobra.Command {
	return &cobra.Command{
		Use:   "pause <session-id> <execution-id> <node-id>",
		Short: "Pause a node in a workflow execution",
		Long: `Pause execution of a specific node within a workflow.

The node will not execute further, but the workflow may continue with other nodes.`,
		Args: cobra.ExactArgs(3),
		RunE: func(cmd *cobra.Command, args []string) error {
			sessionID := args[0]
			executionID := args[1]
			nodeID := args[2]

			resp, err := c.PauseNode(sessionID, executionID, nodeID)
			if err != nil {
				return err
			}

			format := commands.GetOutputFormat()
			if format == "json" {
				output.PrintJSON(resp)
				return nil
			}

			fmt.Printf("Node paused: %s\n", nodeID)
			return nil
		},
	}
}
