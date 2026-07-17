// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package plugin

import (
	"com.infenia.yukta/go-cli/internal/client"
	"com.infenia.yukta/go-cli/internal/commands"
	"com.infenia.yukta/go-cli/internal/output"
	"github.com/spf13/cobra"
)

// ListCmd creates and returns the "plugin list" subcommand.
// It retrieves all available plugins and displays them in table or JSON format.
func ListCmd(c client.ClientInterface) *cobra.Command {
	return &cobra.Command{
		Use:   "list",
		Short: "List all plugins",
		Long:  "Retrieve and display all available plugins from the Yukta API.",
		Args:  cobra.NoArgs,
		RunE: func(cmd *cobra.Command, args []string) error {
			plugins, err := c.ListPlugins()
			if err != nil {
				return err
			}

			format := commands.GetOutputFormat()

			if format == "json" {
				output.PrintJSON(map[string]interface{}{
					"plugins": plugins,
				})
			} else {
				// Table format: Type and Category columns
				rows := make([][]string, len(plugins))
				for i, p := range plugins {
					rows[i] = []string{p.Type, string(p.Category)}
				}
				output.PrintTable([]string{"Type", "Category"}, rows)
			}

			return nil
		},
	}
}
