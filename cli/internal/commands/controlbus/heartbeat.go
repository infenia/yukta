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
func HeartbeatCmd(c *client.Client) *cobra.Command {
	var session, execution string
	cmd := &cobra.Command{
		Use:   "heartbeat --session ID [--execution ID] <workflow-id> <node-id>",
		Short: "Get node heartbeat",
		Long:  "Retrieve and display the last heartbeat for a specific node in a workflow.",
		Args:  cobra.ExactArgs(2),
		RunE: func(cmd *cobra.Command, args []string) error {
			workflowID := args[0]
			nodeID := args[1]

			heartbeat, err := c.GetLastHeartbeat(session, workflowID, execution, nodeID)
			if err != nil {
				return fmt.Errorf("failed to get heartbeat: %w", err)
			}

			format := commands.GetOutputFormat()

			if format == "json" {
				// Always JSON for heartbeat since it's a complex nested structure
				output.PrintJSON(heartbeat)
			} else {
				// Table format: key-value pairs
				output.PrintTable([]string{"Field", "Value"}, buildTableRows(heartbeat))
			}

			return nil
		},
	}
	addNodeFlags(cmd, &session, &execution)
	return cmd
}
