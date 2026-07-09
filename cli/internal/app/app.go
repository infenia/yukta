// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package app

import (
	"com.infenia.yukta/go-cli/internal/client"
	"com.infenia.yukta/go-cli/internal/commands"
	"com.infenia.yukta/go-cli/internal/commands/session"
)

// Run initializes and executes the Yukta CLI application.
// It creates the HTTP client, wires up all commands, and executes them.
// This function is testable and returns an error instead of calling os.Exit().
func Run() error {
	// Create a new HTTP client with empty string (uses default URL from environment or hardcoded default)
	c := client.NewClient("")

	// Create the root command
	rootCmd := commands.RootCmd(c)

	// Add the session command group with all its subcommands
	rootCmd.AddCommand(session.SessionCmd(c))

	// Execute the command and return any error
	return rootCmd.Execute()
}
