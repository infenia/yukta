// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package session

import (
	"com.infenia.yukta/go-cli/internal/client"
	"com.infenia.yukta/go-cli/internal/commands"
	"github.com/spf13/cobra"
)

// SessionCmd creates and returns the "session" command group.
// This is a parent command that organizes session-related operations.
// Subcommands: list, get, apply, workflow, summaries.
func SessionCmd(c *client.Client) *cobra.Command {
	sessionCmd := &cobra.Command{
		Use:   "session",
		Short: "Manage sessions",
		Long: `The session command group provides operations for managing Yukta sessions.

Subcommands allow you to:
  - List all available sessions
  - Get details about a specific session
  - Apply session configurations
  - Manage workflows within sessions`,
		// This is a command group; no Run action needed. Subcommands are added separately.
	}

	sessionCmd.AddCommand(ListCmd(c))
	sessionCmd.AddCommand(GetCmd(c))
	sessionCmd.AddCommand(ApplyCmd(c))
	sessionCmd.AddCommand(DeleteCmd(c))
	sessionCmd.AddCommand(WorkflowCmd(c))
	sessionCmd.AddCommand(dataCmd("summaries", "List all sessions with their workflow summaries", 0,
		func(_ []string) (any, error) { return c.GetSessionSummaries() }))

	sessionCmd.AddCommand(dataCmd("approvals <session-id>", "List the approvals the session's runs wait for", 1,
		func(a []string) (any, error) { return c.GetPendingApprovals(a[0]) }))
	sessionCmd.AddCommand(dataCmd("config <session-id>", "Show the configuration a session was created from", 1,
		func(a []string) (any, error) { return c.GetSessionConfig(a[0]) }))
	sessionCmd.AddCommand(validateCmd(c))

	return sessionCmd
}

// validateCmd builds "session validate <json-or-file>": check a workflow definition without saving it.
func validateCmd(c *client.Client) *cobra.Command {
	return dataCmd("validate <json-or-file>", "Validate a workflow definition without saving it", 1,
		func(a []string) (any, error) {
			body, err := commands.ReadJSONArg(a[0])
			if err != nil {
				return nil, err
			}
			return c.ValidateWorkflow(body)
		})
}
