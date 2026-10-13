// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package node

import (
	"errors"
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

	cmd.AddCommand(commands.Action(commands.ActionSpec[client.WorkflowStartResponse]{
		Use:   "retry <execution-id> <node-id>",
		Short: "Retry a failed node",
		Long: `Restart an execution from a node that ended FAILURE or TIMED_OUT.

The nodes before it replay their recorded output; the node and everything behind it run again.
Prints the id of the new execution. Takes no session id.`,
		Args: cobra.ExactArgs(2),
		Run:  func(a []string) (client.WorkflowStartResponse, error) { return c.RetryNode(a[0], a[1]) },
		Msg: func(r client.WorkflowStartResponse, a []string) string {
			return fmt.Sprintf("Node %s retried; new execution: %s", a[1], r.ExecutionID)
		},
	}))

	var sigName, sigDecision, sigData string
	signalCmd := commands.Action(commands.ActionSpec[client.WorkflowStartResponse]{
		Use:   "signal <execution-id> <node-id>",
		Short: "Send a signal to a waiting node",
		Long: `Release or reject a node that waits for a named signal, for example a WAIT_FOR_SIGNAL
approval step. Fails with HTTP 409 if the node is not waiting for it. Takes no session id.`,
		Args: cobra.ExactArgs(2),
		Run: func(a []string) (client.WorkflowStartResponse, error) {
			return c.SignalNode(a[0], a[1], sigName, sigDecision, sigData)
		},
		Msg: func(_ client.WorkflowStartResponse, a []string) string {
			return fmt.Sprintf("Signal %s=%s sent to node %s", sigName, sigDecision, a[1])
		},
	})
	signalCmd.Flags().StringVar(&sigName, "name", "", "Name of the signal the node waits for")
	signalCmd.Flags().StringVar(&sigDecision, "decision", "", "Decision, for example approve or reject")
	signalCmd.Flags().StringVar(&sigData, "data", "", "Optional text that goes with the decision")
	_ = signalCmd.MarkFlagRequired("name")
	_ = signalCmd.MarkFlagRequired("decision")
	cmd.AddCommand(signalCmd)

	var bpField, bpEquals string
	var bpClear bool
	breakpoint := commands.Action(commands.ActionSpec[client.WorkflowStartResponse]{
		Use:   "breakpoint <execution-id> <node-id>",
		Short: "Set or clear a breakpoint on a node",
		Long: `Hold a node before the first element whose field equals a value, for example
--field payload.status --equals error. Use --clear to remove the node's breakpoint.
Takes no session id.`,
		Args: cobra.ExactArgs(2),
		Run: func(a []string) (client.WorkflowStartResponse, error) {
			if bpClear {
				return c.ClearBreakpoint(a[0], a[1])
			}
			if bpField == "" {
				return client.WorkflowStartResponse{}, errors.New("--field and --equals are required unless --clear is given")
			}
			return c.SetBreakpoint(a[0], a[1], bpField, bpEquals)
		},
		Msg: func(_ client.WorkflowStartResponse, a []string) string {
			if bpClear {
				return "Breakpoint cleared on node " + a[1]
			}
			return fmt.Sprintf("Breakpoint set on node %s: %s == %s", a[1], bpField, bpEquals)
		},
	})
	breakpoint.Flags().StringVar(&bpField, "field", "", "Field to compare, for example payload.status")
	breakpoint.Flags().StringVar(&bpEquals, "equals", "", "Value that triggers the breakpoint")
	breakpoint.Flags().BoolVar(&bpClear, "clear", false, "Remove the node's breakpoint")
	cmd.AddCommand(breakpoint)

	cmd.AddCommand(step.StepCmd(c))
	return cmd
}
