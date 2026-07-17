// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package plugin

import (
	"fmt"

	"com.infenia.yukta/go-cli/internal/client"
	"com.infenia.yukta/go-cli/internal/commands"
	"com.infenia.yukta/go-cli/internal/output"
	"github.com/spf13/cobra"
)

// GetCmd creates and returns the "plugin get" subcommand.
// It retrieves and displays detailed information about a specific plugin.
func GetCmd(c client.ClientInterface) *cobra.Command {
	return &cobra.Command{
		Use:   "get <type>",
		Short: "Get plugin details",
		Long:  "Retrieve and display detailed information about a specific plugin.",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			pluginType := args[0]

			details, err := c.GetPluginDetails(pluginType)
			if err != nil {
				return fmt.Errorf("failed to get plugin details: %w", err)
			}

			format := commands.GetOutputFormat()

			if format == "json" {
				// JSON format: output full details including UI design
				output.PrintJSON(details)
			} else {
				// Table format: key detail fields
				rows := [][]string{
					{"Type", details.Type},
					{"Category", string(details.Category)},
					{"Description", details.Description},
					{"Usage Pattern", details.UsagePattern},
					{"Output Ports", fmt.Sprintf("%v", details.OutputPorts)},
					{"UI Width", fmt.Sprintf("%d", details.UIDesign.Width)},
					{"UI Height", fmt.Sprintf("%d", details.UIDesign.Height)},
				}

				// Print as a key-value table (2 columns)
				output.PrintTable([]string{"Field", "Value"}, rows)
			}

			return nil
		},
	}
}
