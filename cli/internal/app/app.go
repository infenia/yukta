// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package app

import (
	"fmt"
	"os"

	"com.infenia.yukta/go-cli/internal/client"
	"com.infenia.yukta/go-cli/internal/commands"
	"com.infenia.yukta/go-cli/internal/commands/controlbus"
	"com.infenia.yukta/go-cli/internal/commands/logs"
	"com.infenia.yukta/go-cli/internal/commands/plugin"
	"com.infenia.yukta/go-cli/internal/commands/result"
	"com.infenia.yukta/go-cli/internal/commands/scheduler"
	"com.infenia.yukta/go-cli/internal/commands/session"
	"com.infenia.yukta/go-cli/internal/commands/whoami"
	"com.infenia.yukta/go-cli/internal/commands/workflow"
)

// Run initializes and executes the Yukta CLI application.
// It creates the HTTP client, wires up all commands, and executes them.
// This function is testable and returns an error instead of calling os.Exit().
func Run() error {
	_, err := run()
	return err
}

// Main runs the CLI and returns the process exit code, printing a failure once on stderr.
func Main() int {
	code, err := run()
	if err != nil {
		fmt.Fprintf(os.Stderr, "error: %v\n", err)
	}
	return code
}

func run() (int, error) {
	// The root command binds --url, --token and --timeout directly to this client's fields.
	c := client.NewClient("")

	// Create the root command
	rootCmd := commands.RootCmd(c)

	// Add the plugin command group with all its subcommands
	rootCmd.AddCommand(plugin.PluginCmd(c))

	// Add the controlbus command group with all its subcommands
	rootCmd.AddCommand(controlbus.ControlBusCmd(c))
	rootCmd.AddCommand(whoami.WhoamiCmd(c))
	rootCmd.AddCommand(scheduler.SchedulerCmd(c))
	rootCmd.AddCommand(commands.DataCmd("supervisors", "List supervisors", 0,
		func([]string) (any, error) { return c.GetSupervisors() }))
	rootCmd.AddCommand(commands.DataCmd("workers", "List workers", 0,
		func([]string) (any, error) { return c.GetWorkers() }))

	// Add the logs command for streaming execution logs
	rootCmd.AddCommand(logs.LogsCmd(c))
	rootCmd.AddCommand(result.ResultCmd(c))

	// Add the workflow command group with all its subcommands
	rootCmd.AddCommand(workflow.WorkflowCmd(c))

	// Add the session command group with all its subcommands
	rootCmd.AddCommand(session.SessionCmd(c))

	// Execute the command and return any error
	err := rootCmd.Execute()
	return commands.ExitCode(err, !rootCmd.SilenceUsage), err
}
