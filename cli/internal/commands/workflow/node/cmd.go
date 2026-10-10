// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package node

import (
	"fmt"

	"com.infenia.yukta/go-cli/internal/client"
	"com.infenia.yukta/go-cli/internal/commands"
	"com.infenia.yukta/go-cli/internal/commands/workflow/node/step"
	"github.com/spf13/cobra"
)

// NodeCmd creates the "workflow node" command group.
func NodeCmd(c *client.Client) *cobra.Command {
	cmd := &cobra.Command{
		Use:   "node",
		Short: "Control individual nodes in a workflow",
		Long: `Control individual nodes within a workflow execution,
including pausing, resuming, stopping, skipping, and step-through debugging.`,
	}

	// nodeAction builds one "<verb> <session-id> <execution-id> <node-id>" subcommand.
	nodeAction := func(verb, short, long string, msg func(node string) string,
		call func(s, e, n string) (client.WorkflowStartResponse, error),
	) *cobra.Command {
		return commands.Action(commands.ActionSpec[client.WorkflowStartResponse]{
			Use:   verb + " <session-id> <execution-id> <node-id>",
			Short: short,
			Long:  long,
			Args:  cobra.ExactArgs(3),
			Run:   func(a []string) (client.WorkflowStartResponse, error) { return call(a[0], a[1], a[2]) },
			Msg:   func(_ client.WorkflowStartResponse, a []string) string { return msg(a[2]) },
		})
	}

	cmd.AddCommand(nodeAction("pause", "Pause a node in a workflow execution",
		`Pause execution of a specific node within a workflow.

The node will not execute further, but the workflow may continue with other nodes.`,
		func(n string) string { return "Node paused: " + n }, c.PauseNode))

	cmd.AddCommand(nodeAction("resume", "Resume a paused node in a workflow execution",
		`Resume execution of a paused node within a workflow.

The node will continue executing from where it was paused.`,
		func(n string) string { return "Node resumed: " + n }, c.ResumeNode))

	var immediate bool
	var reason string
	stop := nodeAction("stop", "Stop a node in a workflow execution",
		`Stop execution of a specific node within a workflow.

The node will be terminated immediately or gracefully depending on the --immediate flag.
Optionally provide a reason for the stop using --reason.`,
		func(n string) string { return "Node stopped: " + n },
		func(s, e, n string) (client.WorkflowStartResponse, error) {
			return c.StopNode(s, e, n, immediate, reason)
		})
	stop.Flags().BoolVar(&immediate, "immediate", false, "Stop the node immediately without graceful shutdown")
	stop.Flags().StringVar(&reason, "reason", "", "Reason for stopping the node")
	cmd.AddCommand(stop)

	var skip bool
	skipCmd := nodeAction("skip", "Skip a node in a workflow execution",
		`Mark a node as skipped or unskipped in a workflow execution.

By default, marks the node as skipped. Use --skip=false to unskip the node.`,
		func(n string) string {
			if skip {
				return fmt.Sprintf("Node %s: skipped", n)
			}
			return fmt.Sprintf("Node %s: unskipped", n)
		},
		func(s, e, n string) (client.WorkflowStartResponse, error) { return c.SkipNode(s, e, n, skip) })
	skipCmd.Flags().BoolVar(&skip, "skip", true, "Whether to skip the node (default true)")
	cmd.AddCommand(skipCmd)

	cmd.AddCommand(step.StepCmd(c))
	return cmd
}
