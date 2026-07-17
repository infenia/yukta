// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package controlbus

import (
	"com.infenia.yukta/go-cli/internal/client"
	"com.infenia.yukta/go-cli/internal/commands"
	"com.infenia.yukta/go-cli/internal/output"
	"github.com/spf13/cobra"
)

// AllNodesCmd creates and returns the "controlbus all-nodes" subcommand.
// It retrieves and displays all active nodes across all workflows.
func AllNodesCmd(c client.ClientInterface) *cobra.Command {
	return &cobra.Command{
		Use:   "all-nodes",
		Short: "Get all active nodes globally",
		Long:  "Retrieve and display all active nodes across all workflows.",
		RunE: func(cmd *cobra.Command, args []string) error {
			nodes, err := c.GetAllActiveNodes()
			if err != nil {
				return err
			}

			format := commands.GetOutputFormat()

			if format == "json" {
				output.PrintJSON(map[string]interface{}{
					"nodes": nodes,
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
