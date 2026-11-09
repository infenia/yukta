// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

// Package supervisors holds the "supervisors" command group.
package supervisors

import (
	"fmt"
	"os"
	"strings"

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
		enginesCmd(c),
		commands.DataCmd("list", "List the configured supervisors", 0,
			func([]string) (any, error) { return c.ListSupervisors() }),
		commands.DataCmd("show <id>", "Show one configured supervisor", 1,
			func(args []string) (any, error) { return c.GetSupervisor(args[0]) }),
		commands.DataCmd("presets <engine>", "List the ready-made settings of an engine", 1,
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
		commands.DataCmd("assignments <id>", "List what a supervisor owns, whatever made it", 1,
			func(args []string) (any, error) { return c.GetAssignments(args[0]) }),
		assignCmd(c),
		unassignCmd(c),
		checkAssignCmd(c),
		setAssignmentsCmd(c),
		parentsCmd(c),
		credentialCmd(c),
		agentCmd(c),
	)
	return cmd
}

// assignCmd adds a target to what a supervisor owns.
func assignCmd(c *client.Client) *cobra.Command {
	var disabled, locked bool
	cmd := commands.DataCmd("assign <id> <target>",
		"Make a supervisor own a target too: server, session:<id>, workflow:<s>/<w>, run:<s>/<w> or workflow:<s>/<w>//<selector>,...", 2,
		func(args []string) (any, error) { return c.Assign(args[0], args[1], !disabled, locked) })
	cmd.Flags().BoolVar(&disabled, "disabled", false, "Save it without running it")
	cmd.Flags().BoolVar(&locked, "locked", false, "Only a lock holder may change or remove it")
	return cmd
}

// checkAssignCmd checks what a supervisor would own with more targets, without saving.
func checkAssignCmd(c *client.Client) *cobra.Command {
	cmd := commands.DataCmd("check-assign <id> <target>...", "Check what a supervisor would own with these targets, without saving", 2,
		func(args []string) (any, error) { return c.CheckAssign(args[0], args[1:]) })
	cmd.Args = cobra.MinimumNArgs(2)
	return cmd
}

// unassignCmd removes a target from what a supervisor owns.
func unassignCmd(c *client.Client) *cobra.Command {
	var unlock bool
	cmd := commands.DataCmd("unassign <id> <target>", "Stop a supervisor owning a target it owns by hand", 2,
		func(args []string) (any, error) { return c.Unassign(args[0], args[1], unlock) })
	cmd.Flags().BoolVar(&unlock, "unlock", false, "Allow removing a locked assignment (lock holders only)")
	return cmd
}

// setAssignmentsCmd replaces what a supervisor owns from a JSON file of {target, enabled, locked}.
func setAssignmentsCmd(c *client.Client) *cobra.Command {
	var file string
	var unlock bool
	cmd := commands.DataCmd("set-assignments <id>", "Make a supervisor own exactly the targets of a JSON file (-f)", 1,
		func(args []string) (any, error) {
			body, err := os.ReadFile(file) //nolint:gosec // the path is the user's own argument
			if err != nil {
				return nil, err
			}
			return c.SetAssignmentsBody(args[0], body, unlock)
		})
	cmd.Flags().StringVarP(&file, "file", "f", "", "A JSON list of {target, enabled, locked}")
	cmd.Flags().BoolVar(&unlock, "unlock", false, "Allow changing locked assignments (lock holders only)")
	_ = cmd.MarkFlagRequired("file")
	return cmd
}

// parentsCmd sets who a supervisor reports to.
func parentsCmd(c *client.Client) *cobra.Command {
	group := &cobra.Command{Use: "parents", Short: "Set the supervisors a supervisor reports to"}
	var parents, modes []string
	set := commands.DataCmd("set <id>", "Make a supervisor report to these parents, in order", 1,
		func(args []string) (any, error) {
			byKind := map[string]string{}
			for _, m := range modes {
				kind, mode, ok := strings.Cut(m, "=")
				if !ok {
					return nil, fmt.Errorf("--mode is <kind>=<mode>, such as approval=ordered: %q", m)
				}
				byKind[kind] = mode
			}
			return c.SetParents(args[0], parents, byKind)
		})
	set.Flags().StringArrayVar(&parents, "parent", nil, "A parent, in the order they are asked (repeat)")
	set.Flags().StringArrayVar(&modes, "mode", nil, "approval|input|consult=ordered|all|any (repeat)")
	group.AddCommand(set)
	return group
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
		commands.DataCmd("show <id>", "Show when the credential was issued and last used (never the secret)", 1,
			func(args []string) (any, error) { return c.GetAgentCredential(args[0]) }),
		commands.DataCmd("revoke <id>", "Cut the credential at once", 1,
			func(args []string) (any, error) { return c.RevokeAgentCredential(args[0]) }))
	return group
}

// enginesCmd lists the decision engines, which are the supervisor types.
func enginesCmd(c *client.Client) *cobra.Command {
	cmd := commands.DataCmd("engines [type]", "List the decision engines, or describe one with its settings", 0,
		func(args []string) (any, error) {
			if len(args) == 1 {
				return c.GetSupervisorEngine(args[0])
			}
			return c.GetSupervisorEngines()
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

// OwnershipCmd creates "ownership": which supervisor carries each part of a workflow.
func OwnershipCmd(c *client.Client) *cobra.Command {
	var session, workflow string
	cmd := commands.DataCmd("ownership", "Show which supervisor owns the run level and each node of a workflow", 0,
		func([]string) (any, error) { return c.GetOwnership(session, workflow) })
	cmd.Flags().StringVar(&session, "session", "", "The session")
	cmd.Flags().StringVar(&workflow, "workflow", "", "The workflow")
	_ = cmd.MarkFlagRequired("session")
	_ = cmd.MarkFlagRequired("workflow")
	return cmd
}
