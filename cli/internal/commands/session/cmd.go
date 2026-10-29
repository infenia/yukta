// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package session

import (
	"com.infenia.yukta/go-cli/internal/client"
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

	return sessionCmd
}
