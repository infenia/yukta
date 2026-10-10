// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package workflow

import (
	"fmt"

	"com.infenia.yukta/go-cli/internal/client"
	"com.infenia.yukta/go-cli/internal/commands"
	"com.infenia.yukta/go-cli/internal/commands/workflow/executions"
	"com.infenia.yukta/go-cli/internal/commands/workflow/node"
	"github.com/spf13/cobra"
)

// WorkflowCmd creates and returns the "workflow" parent command.
func WorkflowCmd(c *client.Client) *cobra.Command {
	cmd := &cobra.Command{
		Use:   "workflow",
		Short: "Manage workflow executions",
		Long: `Manage workflow executions, including starting, stopping, pausing, resuming,
and controlling individual nodes within a workflow.`,
	}

	cmd.AddCommand(StartCmd(c))
	cmd.AddCommand(commands.Action(commands.ActionSpec[client.WorkflowStopResponse]{
		Use:   "stop <session-id> <workflow-id>",
		Short: "Stop a workflow execution",
		Long: `Stop all active executions of a workflow in a given session.

Returns the list of execution IDs that were stopped.`,
		Args: cobra.ExactArgs(2),
		Run:  func(a []string) (client.WorkflowStopResponse, error) { return c.StopWorkflow(a[0], a[1]) },
		Msg: func(r client.WorkflowStopResponse, _ []string) string {
			out := fmt.Sprintf("Stopped %d execution(s)", len(r.ExecutionIDs))
			for _, id := range r.ExecutionIDs {
				out += "\n  - " + id
			}
			return out
		},
	}))
	cmd.AddCommand(executionAction("pause", "Pause a workflow execution",
		`Pause an active workflow execution.

Returns the execution ID of the paused workflow.`,
		"Execution paused: %s", c.PauseWorkflow))
	cmd.AddCommand(executionAction("resume", "Resume a paused workflow execution",
		`Resume a paused workflow execution.

Returns the execution ID of the resumed workflow.`,
		"Execution resumed: %s", c.ResumeWorkflow))
	cmd.AddCommand(StatusCmd(c))
	cmd.AddCommand(StatusStreamCmd(c))
	cmd.AddCommand(HistoryCmd(c))
	cmd.AddCommand(executions.ExecutionsCmd(c))
	cmd.AddCommand(node.NodeCmd(c))

	return cmd
}

// executionAction builds a "<verb> <session-id> <execution-id>" subcommand.
func executionAction(verb, short, long, done string,
	call func(sessionID, executionID string) (client.WorkflowStartResponse, error),
) *cobra.Command {
	return commands.Action(commands.ActionSpec[client.WorkflowStartResponse]{
		Use:   verb + " <session-id> <execution-id>",
		Short: short,
		Long:  long,
		Args:  cobra.ExactArgs(2),
		Run:   func(a []string) (client.WorkflowStartResponse, error) { return call(a[0], a[1]) },
		Msg: func(r client.WorkflowStartResponse, _ []string) string {
			return fmt.Sprintf(done, r.ExecutionID)
		},
	})
}
