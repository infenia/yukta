// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

// Package supervisors holds the "supervisors" command group.
package supervisors

import (
	"com.infenia.yukta/go-cli/internal/client"
	"com.infenia.yukta/go-cli/internal/commands"
	"github.com/spf13/cobra"
)

// SupervisorsCmd creates the "supervisors" group. On its own it shows what runs (scope and command
// counts); "types" shows the catalog, "list" the configured supervisors and "show" one of them.
func SupervisorsCmd(c *client.Client) *cobra.Command {
	cmd := commands.DataCmd("supervisors", "Show the running supervisors, the catalog and the configured supervisors", 0,
		func([]string) (any, error) { return c.GetSupervisors() })
	cmd.AddCommand(
		typesCmd(c),
		commands.DataCmd("list", "List the configured supervisors", 0,
			func([]string) (any, error) { return c.ListSupervisors() }),
		commands.DataCmd("show <id>", "Show one configured supervisor", 1,
			func(args []string) (any, error) { return c.GetSupervisor(args[0]) }),
	)
	return cmd
}

// typesCmd lists the catalog, or describes one type when given its name.
func typesCmd(c *client.Client) *cobra.Command {
	cmd := commands.DataCmd("types [type]", "List the supervisor types, or describe one with its settings", 0,
		func(args []string) (any, error) {
			if len(args) == 1 {
				return c.GetSupervisorType(args[0])
			}
			return c.GetSupervisorTypes()
		})
	cmd.Args = cobra.MaximumNArgs(1)
	return cmd
}
