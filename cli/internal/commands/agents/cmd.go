// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

// Package agents holds the "agents" command: it connects AI agents (Claude Code, Codex, Cursor, ...) to Yukta.
package agents

import (
	"errors"
	"fmt"
	"strings"

	ag "com.infenia.yukta/go-cli/internal/agents"
	"com.infenia.yukta/go-cli/internal/client"
	"com.infenia.yukta/go-cli/internal/commands"
	"com.infenia.yukta/go-cli/internal/output"
	"github.com/spf13/cobra"
)

// defaultEnv is the real machine; tests replace it.
var defaultEnv = ag.DefaultEnv

// AgentsCmd creates the "agents" command group.
func AgentsCmd(c *client.Client) *cobra.Command {
	cmd := &cobra.Command{
		Use:   "agents",
		Short: "Connect AI agents (Claude Code, Codex, Cursor, ...) to Yukta",
		Long: `Register the Yukta MCP server in the AI agents on this machine and give them the Yukta skills,
so an agent can run and steer workflows through MCP, this CLI or REST.

Supported: ` + strings.Join(ag.IDs(), ", ") + `.
Changes are limited to a "yukta" entry in each agent's own configuration and to the skills folders
yukta-workflows and yukta-supervisor. They are recorded, so "agents uninstall" removes exactly them.`,
		RunE: func(cmd *cobra.Command, _ []string) error { return cmd.Help() },
	}
	cmd.AddCommand(installCmd(c), uninstallCmd(c), listCmd(c))
	return cmd
}

// installer builds the installer for the machine; progress lines go to stderr so -o json stays clean.
func installer(cmd *cobra.Command, c *client.Client, o ag.Options) (*ag.Installer, error) {
	env, err := defaultEnv()
	if err != nil {
		return nil, err
	}
	o.URL = strings.TrimRight(c.BaseURL, "/") + "/mcp"
	o.Token = c.Token
	return &ag.Installer{Env: env, Opts: o, Out: cmd.ErrOrStderr()}, nil
}

func installCmd(c *client.Client) *cobra.Command {
	var ids []string
	var o ag.Options
	var skillsOnly, mcpOnly, refresh bool
	cmd := &cobra.Command{
		Use:   "install",
		Short: "Connect agents to the server at --url",
		Long: `Connect the agents named by --agent (default: every detected one) to the server at --url.
A server with API keys needs one: pass --token-file (or --token-env to let the agent read it from an
environment variable). Claude Code gets the Yukta plugin when the server is the default local one.`,
		Args: cobra.NoArgs,
		RunE: func(cmd *cobra.Command, _ []string) error {
			if skillsOnly && mcpOnly {
				return errors.New("--skills-only and --mcp-only cannot be combined")
			}
			o.NoMCP, o.NoSkills = skillsOnly, mcpOnly
			in, err := installer(cmd, c, o)
			if err != nil {
				return err
			}
			if refresh {
				rs, err := in.Refresh()
				return report(rs, err)
			}
			selected, err := ag.Select(in.Env, ids)
			if err != nil {
				return err
			}
			if len(selected) == 0 {
				_, err := fmt.Fprintf(cmd.OutOrStdout(), "No supported AI agent found. Name one with --agent (%s).\n", strings.Join(ag.IDs(), ", "))
				return err
			}
			rs, err := in.Install(selected)
			if err == nil {
				serverNotes(cmd, c, in.Opts)
			}
			return report(rs, err)
		},
	}
	cmd.Flags().StringSliceVar(&ids, "agent", nil, "Agents to connect: a list, \"all\", or \"auto\" (default: the detected ones)")
	cmd.Flags().BoolVar(&skillsOnly, "skills-only", false, "Only copy the skills, do not register the MCP server")
	cmd.Flags().BoolVar(&mcpOnly, "mcp-only", false, "Only register the MCP server, do not copy skills")
	cmd.Flags().StringVar(&o.TokenEnv, "token-env", "", "Name of an environment variable the agents read the API key from, instead of a written key")
	cmd.Flags().BoolVar(&o.DryRun, "dry-run", false, "Show what would change and change nothing")
	cmd.Flags().BoolVar(&refresh, "refresh", false, "Copy the current skills again for the agents already connected (after an upgrade)")
	return cmd
}

// serverNotes tells what a quick look at the server says about the setup; it never fails the install.
func serverNotes(cmd *cobra.Command, c *client.Client, o ag.Options) {
	who, err := c.GetWhoami()
	switch {
	case err != nil:
		cmd.PrintErrf("note: no Yukta server answered at %s; the agents are set up and connect once it runs\n", c.BaseURL)
	case who.AuthEnabled && o.Token == "" && o.TokenEnv == "":
		cmd.PrintErrln("note: this server needs an API key; run again with --token-file <file> (or --token-env VAR)")
	}
}

func uninstallCmd(c *client.Client) *cobra.Command {
	var ids []string
	var all bool
	cmd := &cobra.Command{
		Use:   "uninstall",
		Short: "Remove what install added",
		Args:  cobra.NoArgs,
		RunE: func(cmd *cobra.Command, _ []string) error {
			if len(ids) == 0 && !all {
				return errors.New("name the agents with --agent, or use --all")
			}
			in, err := installer(cmd, c, ag.Options{})
			if err != nil {
				return err
			}
			var selected []ag.Agent
			if all {
				selected, err = in.Installed()
			} else {
				selected, err = ag.Select(in.Env, ids)
			}
			if err != nil {
				return err
			}
			rs, err := in.Uninstall(selected)
			return report(rs, err)
		},
	}
	cmd.Flags().StringSliceVar(&ids, "agent", nil, "Agents to disconnect")
	cmd.Flags().BoolVar(&all, "all", false, "Disconnect every agent Yukta connected")
	return cmd
}

func listCmd(c *client.Client) *cobra.Command {
	return &cobra.Command{
		Use:   "list",
		Short: "Show the agents found on this machine and which are connected",
		Args:  cobra.NoArgs,
		RunE: func(cmd *cobra.Command, _ []string) error {
			in, err := installer(cmd, c, ag.Options{})
			if err != nil {
				return err
			}
			st, err := in.List()
			if err != nil {
				return err
			}
			if commands.GetOutputFormat() == "json" {
				output.PrintJSON(st)
				return nil
			}
			rows := make([][]string, 0, len(st))
			for _, s := range st {
				rows = append(rows, []string{s.ID, s.Name, yesNo(s.Detected), yesNo(s.Installed)})
			}
			output.PrintTable([]string{"ID", "AGENT", "FOUND", "CONNECTED"}, rows)
			return nil
		},
	}
}

func yesNo(b bool) string {
	if b {
		return "yes"
	}
	return "no"
}

// report prints the results and turns a failed agent into a non-zero exit.
func report(rs []ag.Result, err error) error {
	if err != nil {
		return err
	}
	if commands.GetOutputFormat() == "json" {
		output.PrintJSON(rs)
	} else {
		rows := make([][]string, 0, len(rs))
		for _, r := range rs {
			rows = append(rows, []string{r.ID, r.Status, r.Detail})
		}
		output.PrintTable([]string{"AGENT", "STATUS", "DETAIL"}, rows)
	}
	if ag.Failed(rs) {
		return errors.New("some agents could not be set up (see above)")
	}
	return nil
}
