// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package session

import (
	"fmt"

	"com.infenia.yukta/go-cli/internal/client"
	"com.infenia.yukta/go-cli/internal/commands"
	"com.infenia.yukta/go-cli/internal/output"
	"github.com/spf13/cobra"
)

// WorkflowCmd creates and returns the "workflow" command group.
// It serves as a parent command for workflow-related subcommands (e.g., "get").
// The command is not meant to be executed directly; it acts as a container for subcommands.
func WorkflowCmd(c client.ClientInterface) *cobra.Command {
	cmd := &cobra.Command{
		Use:   "workflow",
		Short: "Workflow commands",
		Long: `Workflow commands for retrieving and managing workflow definitions within sessions.

Use "yukta session workflow <subcommand>" to perform workflow-related operations.`,
	}

	// Add subcommands to the workflow command group
	cmd.AddCommand(WorkflowGetCmd(c))

	return cmd
}

// WorkflowGetCmd creates and returns the "workflow get" subcommand.
// It retrieves and displays the definition of a specific workflow within a session.
// The command requires exactly two arguments: session ID and workflow ID.
// Output format is controlled by the global -o/--output flag (json or table).
// For complex structures like workflows, output is formatted as JSON in both cases.
func WorkflowGetCmd(c client.ClientInterface) *cobra.Command {
	cmd := &cobra.Command{
		Use:   "get <session-id> <workflow-id>",
		Short: "Get workflow definition",
		Long: `Get the definition of a specific workflow within a session.

This command retrieves the complete workflow definition including nodes, edges,
and other configuration details.

Usage:
  yukta session workflow get <session-id> <workflow-id>
  yukta session workflow get session-123 workflow-456 -o json`,
		Args: cobra.ExactArgs(2),
		RunE: func(cmd *cobra.Command, args []string) error {
			sessionID := args[0]
			workflowID := args[1]

			// Call the client to retrieve the workflow
			workflowData, err := c.GetWorkflow(sessionID, workflowID)
			if err != nil {
				return fmt.Errorf("failed to retrieve workflow: %w", err)
			}

			// Get the output format from the global flag
			format := commands.GetOutputFormat()

			// Format and print the output
			// For workflows (complex structures), always use JSON format
			// as table format would not be suitable for nested data
			if format == "json" || format == "table" {
				output.PrintJSON(workflowData)
			}

			return nil
		},
	}

	return cmd
}
