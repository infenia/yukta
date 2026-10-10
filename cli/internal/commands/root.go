// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package commands

import (
	"os"

	"com.infenia.yukta/go-cli/internal/client"
	"github.com/spf13/cobra"
)

// outputFormat holds the -o/--output value, read by subcommands via GetOutputFormat.
var outputFormat string

// RootCmd creates and returns the root Cobra command with global flags.
// The --url, --timeout and --token flags are bound straight to the client's fields, so
// there is no separate sync step after parsing; -o/--output is inherited by all subcommands.
func RootCmd(c *client.Client) *cobra.Command {
	// Determine the default server URL: env var takes precedence, then hardcoded default.
	defaultURL := os.Getenv("YUKTA_SERVER_URL")
	if defaultURL == "" {
		defaultURL = "http://localhost:8080"
	}

	cmd := &cobra.Command{
		Use:   "yukta",
		Short: "Yukta CLI - Code quality gates for AI-driven development",
		Long: `Yukta is a high-performance CLI tool for enforcing code quality gates
through AI-driven development workflows. It provides session management,
workflow orchestration, and integration with quality check plugins.`,
		// With no subcommand, show help instead of Cobra's "no subcommands" error.
		RunE: func(cmd *cobra.Command, args []string) error {
			return cmd.Help()
		},
	}

	// Define persistent flags that are inherited by all subcommands.
	// --url flag for specifying the server URL.
	cmd.PersistentFlags().StringVar(
		&c.BaseURL,
		"url",
		defaultURL,
		"Server URL (env: YUKTA_SERVER_URL)",
	)

	cmd.PersistentFlags().DurationVar(
		&c.Timeout,
		"timeout",
		client.DefaultTimeout,
		"Timeout for non-streaming requests (0 disables)",
	)

	cmd.PersistentFlags().StringVar(
		&c.Token,
		"token",
		os.Getenv("YUKTA_TOKEN"),
		"Bearer token for authenticated servers (env: YUKTA_TOKEN)",
	)

	// -o/--output flag for specifying the output format (table or json).
	cmd.PersistentFlags().StringVarP(
		&outputFormat,
		"output",
		"o",
		"table",
		"Output format: table or json",
	)

	return cmd
}

// GetOutputFormat returns the output format flag value.
// This helper function allows subcommands to easily access the global output format setting.
func GetOutputFormat() string {
	return outputFormat
}

// SetTestOutputFormat is a helper function to set output format for testing.
// This allows tests to override the global outputFormat variable for test isolation.
func SetTestOutputFormat(format string) {
	outputFormat = format
}
