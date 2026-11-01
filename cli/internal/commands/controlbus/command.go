// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package controlbus

import (
	"encoding/json"
	"fmt"
	"sort"

	"com.infenia.yukta/go-cli/internal/client"
	"com.infenia.yukta/go-cli/internal/commands"
	"com.infenia.yukta/go-cli/internal/output"
	"github.com/spf13/cobra"
)

// CommandCmd creates and returns the "controlbus command" subcommand.
// It sends a command to a specific node in a workflow.
// The command payload can be provided as either a file path or inline JSON.
func CommandCmd(c *client.Client) *cobra.Command {
	var session, execution string
	cmd := &cobra.Command{
		Use:   "command --session ID [--execution ID] <workflow-id> <node-id> <json-or-file>",
		Short: "Send command to a node",
		Long: `Send a command to a specific node in a workflow.

The command payload can be provided in two ways:
  1. As a file path (e.g., command.json)
  2. As an inline JSON string (e.g., '{"action":"pause"}')

The command will automatically detect whether the input is a file or JSON string.

Workflow ids are only unique within a session, so --session is required. Name the run with
--execution when the session has several live runs of the workflow.`,
		Args: cobra.ExactArgs(3),
		RunE: func(cmd *cobra.Command, args []string) error {
			workflowID := args[0]
			nodeID := args[1]
			input := args[2]

			// Read command payload from file or inline JSON
			commandPayload, err := commands.ReadJSONArg(input)
			if err != nil {
				return fmt.Errorf("failed to read command: %w", err)
			}

			// Validate JSON before sending
			var commandData interface{}
			if err := json.Unmarshal(commandPayload, &commandData); err != nil {
				return fmt.Errorf("invalid JSON: %w", err)
			}

			// Send the command to the server
			response, err := c.SendCommand(session, workflowID, execution, nodeID, commandPayload)
			if err != nil {
				return fmt.Errorf("failed to send command: %w", err)
			}

			return formatAndPrintResponse(response)
		},
	}
	addNodeFlags(cmd, &session, &execution)
	return cmd
}

// addNodeFlags adds the flags that say which session and run a node-level call addresses.
func addNodeFlags(cmd *cobra.Command, session, execution *string) {
	cmd.Flags().StringVar(session, "session", "", "Session that owns the workflow (required)")
	cmd.Flags().StringVar(execution, "execution", "", "Run; needed only when the session has several live runs of the workflow")
	_ = cmd.MarkFlagRequired("session")
}

// formatAndPrintResponse formats and prints the command response based on the output format.
// It handles both JSON and table output formats.
func formatAndPrintResponse(response map[string]interface{}) error {
	format := commands.GetOutputFormat()

	if format == "json" {
		output.PrintJSON(response)
	} else {
		rows := buildTableRows(response)
		output.PrintTable([]string{"Field", "Value"}, rows)
	}

	return nil
}

// buildTableRows converts a response map into table rows for formatted output.
// Keys are sorted for deterministic row order across invocations.
func buildTableRows(response map[string]interface{}) [][]string {
	keys := make([]string, 0, len(response))
	for key := range response {
		keys = append(keys, key)
	}
	sort.Strings(keys)

	rows := make([][]string, 0, len(keys))
	for _, key := range keys {
		rows = append(rows, []string{key, fmt.Sprintf("%v", response[key])})
	}
	return rows
}
