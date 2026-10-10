// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package step

import (
	"fmt"

	"com.infenia.yukta/go-cli/internal/client"
	"com.infenia.yukta/go-cli/internal/commands"
	"github.com/spf13/cobra"
)

// StepCmd creates the "workflow node step" command group.
func StepCmd(c *client.Client) *cobra.Command {
	cmd := &cobra.Command{
		Use:   "step",
		Short: "Control step-through debugging of nodes",
		Long: `Control step-through debugging for individual nodes in a workflow execution.
Step mode allows you to pause the workflow and execute one node at a time.`,
	}

	// step builds one "<verb> <session-id> <execution-id> <node-id>" subcommand.
	step := func(verb, short, long, done string, call func(s, e, n string) (client.WorkflowStartResponse, error)) {
		cmd.AddCommand(commands.Action(commands.ActionSpec[client.WorkflowStartResponse]{
			Use:   verb + " <session-id> <execution-id> <node-id>",
			Short: short,
			Long:  long,
			Args:  cobra.ExactArgs(3),
			Run:   func(a []string) (client.WorkflowStartResponse, error) { return call(a[0], a[1], a[2]) },
			Msg: func(_ client.WorkflowStartResponse, a []string) string {
				return fmt.Sprintf(done, a[2])
			},
		}))
	}

	step("enable", "Enable step-through debugging for a node",
		`Enable step-through debug mode for a specific node.
When enabled, the workflow execution will pause before executing this node,
allowing you to step through its execution one step at a time.`,
		"Step mode enabled for node: %s", c.EnableStepMode)
	step("disable", "Disable step-through debugging for a node",
		`Disable step-through debug mode for a specific node.
The workflow execution will resume normal execution for this node.`,
		"Step mode disabled for node: %s", c.DisableStepMode)
	step("next", "Step to the next execution in a node",
		`Execute the next step in a node that is in step-through debug mode.

This command is used when a node has step mode enabled to progress execution one step at a time.`,
		"Stepped to next execution in node: %s", c.StepNode)

	return cmd
}
