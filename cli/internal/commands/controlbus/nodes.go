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

// NodesCmd creates and returns the "controlbus nodes" subcommand.
// It retrieves and displays active nodes in a specific workflow.
func NodesCmd(c client.ClientInterface) *cobra.Command {
	return &cobra.Command{
		Use:   "nodes <workflow-id>",
		Short: "Get active nodes in a workflow",
		Long:  "Retrieve and display all active nodes in a specific workflow.",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			workflowID := args[0]

			nodes, err := c.GetActiveNodesInWorkflow(workflowID)
			if err != nil {
				return fmt.Errorf("failed to get active nodes: %w", err)
			}

			format := commands.GetOutputFormat()

			if format == "json" {
				output.PrintJSON(map[string]interface{}{
					"workflowId": workflowID,
					"nodes":      nodes,
				})
			} else {
				// Table format: single column with node IDs
				rows := make([][]string, len(nodes))
				for i, node := range nodes {
					rows[i] = []string{node}
				}
				output.PrintTable([]string{"Node ID"}, rows)
			}

			return nil
		},
	}
}
