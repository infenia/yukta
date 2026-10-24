// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package commands

import (
	"errors"
	"fmt"
	"os"
	"strconv"
	"strings"
	"time"

	"com.infenia.yukta/go-cli/internal/client"
	"github.com/spf13/cobra"
)

// outputFormat holds the -o/--output value, read by subcommands via GetOutputFormat.
var outputFormat string

// Version is the release version, set at build time (-ldflags -X, see cli/Makefile).
var Version = "dev"

// RootCmd creates and returns the root Cobra command with global flags.
// The --url, --timeout and --token flags are bound straight to the client's fields, so
// there is no separate sync step after parsing; -o/--output is inherited by all subcommands.
func RootCmd(c *client.Client) *cobra.Command {
	// Determine the default server URL: env var takes precedence, then hardcoded default.
	defaultURL := os.Getenv("YUKTA_SERVER_URL")
	if defaultURL == "" {
		defaultURL = "http://localhost:8080"
	}

	// Stream limits are deployment properties (a proxy that buffers SSE), so env only, no flags.
	// An unset, unparsable or negative value keeps the default.
	if d, err := time.ParseDuration(os.Getenv("YUKTA_STREAM_IDLE")); err == nil && d >= 0 {
		c.StreamIdle = d // 0 turns the idle check off
	}
	if n, err := strconv.Atoi(os.Getenv("YUKTA_STREAM_RECONNECTS")); err == nil && n >= 0 {
		c.StreamReconnects = n
	}

	cmd := &cobra.Command{
		Use:     "yukta-cli",
		Version: Version,
		Short:   "Yukta CLI - run and steer workflows on a Yukta server",
		Long: `Yukta is a reactive workflow orchestration server. This CLI manages
sessions, starts and controls workflow runs, watches their progress and reads their
logs and results.`,
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

	cmd.PersistentFlags().IntVar(
		&c.Retries,
		"retries",
		0,
		"Extra attempts for reads, and for starts (a key is generated), on network errors, 429 and 502-504",
	)

	cmd.PersistentFlags().DurationVar(
		&c.RetryWait,
		"retry-wait",
		time.Second,
		"First pause between retries (doubles each time; a Retry-After from the server wins if longer)",
	)
	var tokenFile string
	cmd.PersistentFlags().StringVar(
		&tokenFile,
		"token-file",
		"",
		"File holding the bearer token (keeps it out of shell history and the process list)",
	)
	// An unparsable value stays off: the unsafe path is opt-in only.
	insecureFromEnv, _ := strconv.ParseBool(os.Getenv("YUKTA_INSECURE_TOKEN"))
	cmd.PersistentFlags().BoolVar(
		&c.AllowInsecureToken,
		"insecure-token",
		insecureFromEnv,
		"Allow sending the token over plain http to a non-loopback host (env: YUKTA_INSECURE_TOKEN)",
	)
	cmd.PersistentPreRunE = func(run *cobra.Command, _ []string) error {
		// Arguments and flags are parsed by now, so later errors come from the server, not from usage.
		cmd.SilenceUsage = true
		if tokenFile != "" && run.Flags().Changed("token") {
			return errors.New("--token and --token-file are mutually exclusive")
		}
		if tokenFile == "" {
			// Read here, not as the flag default: cobra prints defaults in --help.
			if c.Token == "" {
				c.Token = os.Getenv("YUKTA_TOKEN")
			}
			return nil
		}
		data, err := os.ReadFile(tokenFile) // #nosec G304 -- the user names this file on purpose
		if err != nil {
			return fmt.Errorf("reading --token-file: %w", err)
		}
		c.Token = strings.TrimSpace(string(data))
		return nil
	}

	cmd.PersistentFlags().StringVar(
		&c.Token,
		"token",
		"",
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

	cmd.AddCommand(&cobra.Command{
		Use:   "version",
		Short: "Print the CLI version",
		Args:  cobra.NoArgs,
		RunE: func(cmd *cobra.Command, _ []string) error {
			if GetOutputFormat() == "json" {
				_, err := fmt.Fprintf(cmd.OutOrStdout(), "{\"version\":%q}\n", Version)
				return err
			}
			_, err := fmt.Fprintf(cmd.OutOrStdout(), "yukta-cli %s\n", Version)
			return err
		},
	})

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
