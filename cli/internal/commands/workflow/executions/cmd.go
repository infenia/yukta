// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package executions

import (
	"fmt"
	"strconv"

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
	var mode string
	stop := commands.Action(commands.ActionSpec[client.WorkflowStartResponse]{
		Use:   "stop <execution-id>",
		Short: "Stop a workflow execution",
		Long: `Stop a specific workflow execution by its execution ID. With --tree also every
unfinished run it started as a sub-workflow, at any depth. --mode picks how the run ends: DRAIN
(default) lets work in flight finish, CANCEL cancels it, KILL ends the run at once.

Returns the execution ID of the stopped execution.`,
		Args: cobra.ExactArgs(1),
		Run:  func(a []string) (client.WorkflowStartResponse, error) { return c.StopExecutionMode(a[0], tree, mode) },
		Msg: func(r client.WorkflowStartResponse, _ []string) string {
			return fmt.Sprintf("Execution stopped: %s", r.ExecutionID)
		},
	})
	stop.Flags().BoolVar(&tree, "tree", false, "Also stop the runs it started as sub-workflows")
	stop.Flags().StringVar(&mode, "mode", "", "How to stop: DRAIN (default), CANCEL or KILL")
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

	cmd.AddCommand(commands.DataCmd("recover <execution-id>", "Recover an aborted execution", 1,
		func(a []string) (any, error) { return c.RecoverExecution(a[0]) }))
	cmd.AddCommand(priorityCmd(c))
	var session, filter string
	var limit int
	search := commands.DataCmd("search", "Search runs across sessions", 0,
		func([]string) (any, error) { return c.SearchExecutions(session, filter, limit) })
	search.Flags().StringVar(&session, "session", "", "Only runs of this session")
	search.Flags().StringVar(&filter, "filter", "", "Server-side filter expression")
	search.Flags().IntVar(&limit, "limit", 0, "At most this many runs (default: the server's)")
	cmd.AddCommand(search)

	return cmd
}

// priorityCmd builds "priority <execution-id> <priority>".
func priorityCmd(c *client.Client) *cobra.Command {
	return commands.DataCmd("priority <execution-id> <priority>", "Change the priority of a queued execution", 2,
		func(a []string) (any, error) {
			p, err := strconv.Atoi(a[1])
			if err != nil {
				return nil, fmt.Errorf("priority must be a whole number: %w", err)
			}
			return c.SetExecutionPriority(a[0], p)
		})
}
