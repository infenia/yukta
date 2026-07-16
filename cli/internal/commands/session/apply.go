// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package session

import (
	"encoding/json"
	"fmt"
	"os"

	"com.infenia.yukta/go-cli/internal/client"
	"com.infenia.yukta/go-cli/internal/output"
	"github.com/spf13/cobra"
)

// ApplyCmd creates and returns the "session apply" command.
// It reads configuration from a file or inline JSON string and applies it to the server.
// The command accepts exactly one argument: either a file path or a JSON string.
// It supports output formatting via the -o/--output flag (table or json).
func ApplyCmd(c client.ClientInterface) *cobra.Command {
	var outputFormat string

	cmd := &cobra.Command{
		Use:   "apply <json-or-file>",
		Short: "Apply a session configuration",
		Long: `Apply a session configuration from a file or inline JSON string.

The configuration can be provided in two ways:
  1. As a file path (e.g., config.json)
  2. As an inline JSON string (e.g., '{"sessionId":"my-session"}')

The command will automatically detect whether the input is a file or JSON string.`,
		Args: cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			input := args[0]

			// Attempt to read as a file first
			configJSON, err := readConfigInput(input)
			if err != nil {
				return fmt.Errorf("failed to read configuration: %w", err)
			}

			// Validate JSON before applying
			var configData interface{}
			if err := json.Unmarshal(configJSON, &configData); err != nil {
				return fmt.Errorf("invalid JSON: %w", err)
			}

			// Apply the configuration to the server
			if err := c.ApplyConfig(configJSON); err != nil {
				return fmt.Errorf("failed to apply configuration: %w", err)
			}

			// Format and print the output
			if outputFormat == "json" {
				output.PrintJSON(map[string]string{
					"status":  "success",
					"message": "Configuration applied successfully",
				})
			} else {
				fmt.Println("Session configuration applied successfully")
			}

			return nil
		},
	}

	// Add output format flag
	cmd.Flags().StringVarP(&outputFormat, "output", "o", "table", "Output format (table or json)")

	return cmd
}

// readConfigInput reads configuration from either a file or an inline JSON string.
// It first attempts to check if the input is a file path using os.Stat.
// If the file exists, it reads and returns the file contents.
// If the file doesn't exist, it treats the input as an inline JSON string.
func readConfigInput(input string) ([]byte, error) {
	// Check if the input is a file
	if fileInfo, err := os.Stat(input); err == nil && !fileInfo.IsDir() {
		// File exists and is not a directory, so read it
		return os.ReadFile(input)
	}

	// File doesn't exist or is a directory, treat as inline JSON
	return []byte(input), nil
}
