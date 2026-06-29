// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package session

import (
	"fmt"
	"strings"

	"com.infenia.yukta/go-cli/internal/client"
	"com.infenia.yukta/go-cli/internal/commands"
	"com.infenia.yukta/go-cli/internal/output"
	"github.com/spf13/cobra"
)

// GetCmd creates the "session get" subcommand.
// It retrieves details for a specific session by session ID and displays them
// in the requested output format (table or JSON).
func GetCmd(c client.ClientInterface) *cobra.Command {
	return &cobra.Command{
		Use:   "get <session-id>",
		Short: "Get details for a specific session",
		Long:  `Retrieve and display details for a specific session including its ID and workflow IDs.`,
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			sessionID := args[0]

			// Call the client to retrieve session details
			details, err := c.GetSessionDetails(sessionID)
			if err != nil {
				return fmt.Errorf("failed to get session details: %w", err)
			}

			// Check the output format and format accordingly
			format := commands.GetOutputFormat()

			switch format {
			case "json":
				// Print raw JSON output
				output.PrintJSON(details)
			case "table":
				// Extract session ID and workflow IDs for table display
				var sessionIDValue string
				if sessionIDVal, ok := details["sessionId"].(string); ok {
					sessionIDValue = sessionIDVal
				} else {
					sessionIDValue = sessionID
				}

				// Build headers and row data
				headers := []string{"Session ID"}
				row := []string{sessionIDValue}

				// Add Workflow IDs column if present
				if workflowIDs, ok := details["workflowIds"].([]interface{}); ok && len(workflowIDs) > 0 {
					// Convert workflow IDs to strings and join them
					var workflowIDStrings []string
					for _, id := range workflowIDs {
						if idStr, ok := id.(string); ok {
							workflowIDStrings = append(workflowIDStrings, idStr)
						}
					}
					if len(workflowIDStrings) > 0 {
						headers = append(headers, "Workflow IDs")
						row = append(row, strings.Join(workflowIDStrings, ", "))
					}
				}

				// Print table with headers and data row
				output.PrintTable(headers, [][]string{row})
			default:
				return fmt.Errorf("unsupported output format: %s (use 'table' or 'json')", format)
			}

			return nil
		},
	}
}
