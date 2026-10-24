// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package executions

import (
	"fmt"

	"com.infenia.yukta/go-cli/internal/client"
	"com.infenia.yukta/go-cli/internal/commands"
	"github.com/spf13/cobra"
)

// ExecutionsCmd creates the "workflow executions" command group.
func ExecutionsCmd(c *client.Client) *cobra.Command {
	cmd := &cobra.Command{
		Use:   "executions",
		Short: "Manage workflow executions",
		Long:  `Manage individual workflow executions, including stopping and restarting them.`,
	}

	var tree bool
	stop := commands.Action(commands.ActionSpec[client.WorkflowStartResponse]{
		Use:   "stop <execution-id>",
		Short: "Stop a workflow execution",
		Long: `Stop a specific workflow execution by its execution ID. With --tree also every
unfinished run it started as a sub-workflow, at any depth.

Returns the execution ID of the stopped execution.`,
		Args: cobra.ExactArgs(1),
		Run:  func(a []string) (client.WorkflowStartResponse, error) { return c.StopExecutionTree(a[0], tree) },
		Msg: func(r client.WorkflowStartResponse, _ []string) string {
			return fmt.Sprintf("Execution stopped: %s", r.ExecutionID)
		},
	})
	stop.Flags().BoolVar(&tree, "tree", false, "Also stop the runs it started as sub-workflows")
	cmd.AddCommand(stop)

	cmd.AddCommand(commands.Action(commands.ActionSpec[client.WorkflowStartResponse]{
		Use:   "restart <execution-id> [from-node-id]",
		Short: "Restart a workflow execution",
		Long: `Restart a workflow execution, optionally from a specific node.

If from-node-id is provided, the execution will restart from that node.
Otherwise, it will restart from the beginning.

Returns the execution ID of the new execution.`,
		Args: cobra.RangeArgs(1, 2),
		Run: func(a []string) (client.WorkflowStartResponse, error) {
			if len(a) == 2 {
				return c.RestartFromNode(a[0], a[1])
			}
			return c.RestartExecution(a[0])
		},
		Msg: func(r client.WorkflowStartResponse, _ []string) string {
			return fmt.Sprintf("Execution restarted with ID: %s", r.ExecutionID)
		},
	}))

	return cmd
}
