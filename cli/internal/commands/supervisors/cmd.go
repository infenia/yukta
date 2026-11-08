// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

// Package supervisors holds the "supervisors" command group.
package supervisors

import (
	"fmt"
	"os"

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
		enginesCmd(c),
		commands.DataCmd("list", "List the configured supervisors", 0,
			func([]string) (any, error) { return c.ListSupervisors() }),
		commands.DataCmd("show <id>", "Show one configured supervisor", 1,
			func(args []string) (any, error) { return c.GetSupervisor(args[0]) }),
		commands.DataCmd("presets <type>", "List the ready-made settings of a supervisor type", 1,
			func(args []string) (any, error) { return c.GetSupervisorPresets(args[0]) }),
		commands.DataCmd("versions <id>", "List the saved versions of a supervisor, newest first", 1,
			func(args []string) (any, error) { return c.GetSupervisorVersions(args[0]) }),
		rollbackCmd(c),
		decisionsCmd(c),
		fileCmd(c, "save", "Create or replace a supervisor from a JSON file (-f)", c.SaveSupervisor),
		fileCmd(c, "validate", "Check a draft supervisor from a JSON file (-f) without saving it", c.ValidateSupervisor),
		commands.DataCmd("delete <id>", "Delete a supervisor that nothing uses", 1,
			func(args []string) (any, error) { return c.DeleteSupervisor(args[0]) }),
		replayCmd(c),
		credentialCmd(c),
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

// rollbackCmd makes an earlier version current again.
func rollbackCmd(c *client.Client) *cobra.Command {
	var version int
	cmd := commands.DataCmd("rollback <id>", "Make an earlier version of a supervisor current again", 1,
		func(args []string) (any, error) { return c.RollbackSupervisor(args[0], version) })
	cmd.Flags().IntVar(&version, "version", 0, "The version number, from \"versions\"")
	_ = cmd.MarkFlagRequired("version")
	return cmd
}

// replayCmd tries a supervisor on a past run in dry run.
func replayCmd(c *client.Client) *cobra.Command {
	var execution, settings string
	cmd := commands.DataCmd("replay <id>", "Show what a supervisor would have done on a past run (nothing is sent)", 1,
		func(args []string) (any, error) { return c.ReplaySupervisor(args[0], execution, settings) })
	cmd.Flags().StringVar(&execution, "execution", "", "The past run")
	cmd.Flags().StringVar(&settings, "settings", "", "Draft settings as a JSON object, instead of the stored ones")
	_ = cmd.MarkFlagRequired("execution")
	return cmd
}

// credentialCmd issues and revokes the credential of an http supervisor.
func credentialCmd(c *client.Client) *cobra.Command {
	group := &cobra.Command{Use: "credential", Short: "Issue, rotate or revoke the credential of an http supervisor"}
	var ttl int
	create := commands.DataCmd("create <id>", "Make a credential (shown once); an earlier one stops working", 1,
		func(args []string) (any, error) { return c.IssueAgentCredential(args[0], ttl) })
	create.Flags().IntVar(&ttl, "ttl-hours", 0, "Hours it works (0 for no expiry)")
	group.AddCommand(create,
		commands.DataCmd("revoke <id>", "Cut the credential at once", 1,
			func(args []string) (any, error) { return c.RevokeAgentCredential(args[0]) }))
	return group
}

// enginesCmd lists the decision engines, which are the supervisor types.
func enginesCmd(c *client.Client) *cobra.Command {
	cmd := commands.DataCmd("engines [type]", "List the decision engines, or describe one with its settings", 0,
		func(args []string) (any, error) {
			if len(args) == 1 {
				return c.GetSupervisorType(args[0])
			}
			return c.GetSupervisorTypes()
		})
	cmd.Args = cobra.MaximumNArgs(1)
	return cmd
}

// decisionsCmd lists what the supervisors decided lately.
func decisionsCmd(c *client.Client) *cobra.Command {
	var supervisor string
	var limit int
	cmd := commands.DataCmd("decisions", "List the newest decisions of the supervisors", 0,
		func([]string) (any, error) { return c.GetSupervisorDecisions(supervisor, limit) })
	cmd.Flags().StringVar(&supervisor, "supervisor", "", "Only this supervisor")
	cmd.Flags().IntVar(&limit, "limit", 50, "Most decisions to list")
	return cmd
}

// fileCmd builds a command that sends the JSON file named by -f.
func fileCmd(c *client.Client, name, short string, send func([]byte) (any, error)) *cobra.Command {
	var file string
	cmd := commands.DataCmd(name, short, 0, func([]string) (any, error) {
		body, err := os.ReadFile(file) // #nosec G304 -- the user names the file
		if err != nil {
			return nil, fmt.Errorf("cannot read %s: %w", file, err)
		}
		return send(body)
	})
	cmd.Flags().StringVarP(&file, "file", "f", "", "The JSON file")
	_ = cmd.MarkFlagRequired("file")
	_ = c
	return cmd
}

// SupervisionCmd creates "supervision": who owns a target and whether a command would be allowed.
func SupervisionCmd(c *client.Client) *cobra.Command {
	var session, workflow, execution, node string
	cmd := commands.DataCmd("supervision", "Show who owns a target and what is allowed there", 0,
		func([]string) (any, error) { return c.GetSupervision(session, workflow, execution, node) })
	cmd.Flags().StringVar(&session, "session", "", "The session")
	cmd.Flags().StringVar(&workflow, "workflow", "", "The workflow")
	cmd.Flags().StringVar(&execution, "execution", "", "The execution")
	cmd.Flags().StringVar(&node, "node", "", "The node")
	return cmd
}

// ContractCmd creates "supervisor-contract": the version and operations of the contract.
func ContractCmd(c *client.Client) *cobra.Command {
	return commands.DataCmd("supervisor-contract", "Show the supervisor contract version and its operations", 0,
		func([]string) (any, error) { return c.GetSupervisorContract() })
}
