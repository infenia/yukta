// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package session

import (
	"com.infenia.yukta/go-cli/internal/client"
	"github.com/spf13/cobra"
)

// SessionCmd creates and returns the "session" command group.
// This is a parent command that organizes session-related operations.
// Subcommands (list, get, apply, workflow) will be added by later tasks.
func SessionCmd(c client.ClientInterface) *cobra.Command {
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

	// Subcommands are registered as they are implemented in later tasks:
	// - sessionCmd.AddCommand(ListCmd(c))     // Task 6
	// - sessionCmd.AddCommand(GetCmd(c))      // Task 7
	// - sessionCmd.AddCommand(ApplyCmd(c))    // Task 8
	sessionCmd.AddCommand(ListCmd(c))     // Task 6
	sessionCmd.AddCommand(GetCmd(c))      // Task 7
	sessionCmd.AddCommand(WorkflowCmd(c)) // Task 9

	return sessionCmd
}
