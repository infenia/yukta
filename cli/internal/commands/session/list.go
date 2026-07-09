// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited


package session

import (
	"com.infenia.yukta/go-cli/internal/client"
	"com.infenia.yukta/go-cli/internal/commands"
	"com.infenia.yukta/go-cli/internal/output"
	"github.com/spf13/cobra"
)

// ListCmd creates and returns the "session list" subcommand.
// It retrieves all available sessions from the API and displays them
// in the format specified by the global output flag (table or json).
func ListCmd(c client.ClientInterface) *cobra.Command {
	return &cobra.Command{
		Use:   "list",
		Short: "List all sessions",
		Long:  "Retrieve and display all available sessions from the Yukta API.",
		RunE: func(cmd *cobra.Command, args []string) error {
			// Call the GetSessions API method
			sessions, err := c.GetSessions()
			if err != nil {
				return err
			}

			// Get the output format from the global flag
			format := commands.GetOutputFormat()

			// Format and print the output based on the selected format
			if format == "json" {
				// For JSON format, create a map with "sessions" key
				output.PrintJSON(map[string]interface{}{
					"sessions": sessions,
				})
			} else {
				// For table format, create rows from session IDs
				// Each session ID becomes a single cell row
				rows := make([][]string, len(sessions))
				for i, sessionID := range sessions {
					rows[i] = []string{sessionID}
				}

				// Print table with Session ID header
				output.PrintTable([]string{"Session ID"}, rows)
			}

			return nil
		},
	}
}
