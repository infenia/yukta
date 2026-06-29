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

package commands

import (
	"os"
	"strings"

	"com.infenia.yukta/go-cli/internal/client"
	"github.com/spf13/cobra"
)

// Package-level variables to store global flag values.
// These are set by the root command's persistent flags and accessed by subcommands.
var (
	serverURL    string
	outputFormat string
)

// RootCmd creates and returns the root Cobra command with global flags.
// The root command is configured with --url and -o/--output flags that are
// inherited by all subcommands. The provided client's BaseURL is updated
// based on the resolved server URL during command execution.
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
		// PersistentPreRunE is executed before the command runs, after all flags are parsed.
		// This hook updates the client's BaseURL with the resolved server URL value.
		PersistentPreRunE: func(cmd *cobra.Command, args []string) error {
			c.BaseURL = strings.TrimRight(serverURL, "/")
			return nil
		},
		// RunE ensures the root command still runs its PersistentPreRunE even if no subcommand is provided.
		// Without this, Cobra would return "Error: no subcommands" before calling the PersistentPreRunE hook.
		RunE: func(cmd *cobra.Command, args []string) error {
			return cmd.Help()
		},
	}

	// Define persistent flags that are inherited by all subcommands.
	// --url flag for specifying the server URL.
	cmd.PersistentFlags().StringVar(
		&serverURL,
		"url",
		defaultURL,
		"Server URL (env: YUKTA_SERVER_URL)",
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
