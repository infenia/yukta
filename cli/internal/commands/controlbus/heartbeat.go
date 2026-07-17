// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package controlbus

import (
	"fmt"

	"com.infenia.yukta/go-cli/internal/client"
	"com.infenia.yukta/go-cli/internal/commands"
	"com.infenia.yukta/go-cli/internal/output"
	"github.com/spf13/cobra"
)

// HeartbeatCmd creates and returns the "controlbus heartbeat" subcommand.
// It retrieves and displays the last heartbeat for a specific node.
func HeartbeatCmd(c client.ClientInterface) *cobra.Command {
	return &cobra.Command{
		Use:   "heartbeat <workflow-id> <node-id>",
		Short: "Get node heartbeat",
		Long:  "Retrieve and display the last heartbeat for a specific node in a workflow.",
		Args:  cobra.ExactArgs(2),
		RunE: func(cmd *cobra.Command, args []string) error {
			workflowID := args[0]
			nodeID := args[1]

			heartbeat, err := c.GetLastHeartbeat(workflowID, nodeID)
			if err != nil {
				return fmt.Errorf("failed to get heartbeat: %w", err)
			}

			format := commands.GetOutputFormat()

			if format == "json" {
				// Always JSON for heartbeat since it's a complex nested structure
				output.PrintJSON(heartbeat)
			} else {
				// Table format: key-value pairs
				rows := make([][]string, 0)
				for key, value := range heartbeat {
					rows = append(rows, []string{key, fmt.Sprintf("%v", value)})
				}
				output.PrintTable([]string{"Field", "Value"}, rows)
			}

			return nil
		},
	}
}
