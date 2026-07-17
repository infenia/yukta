// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package workflow

import (
	"com.infenia.yukta/go-cli/internal/client"
	"com.infenia.yukta/go-cli/internal/commands/workflow/executions"
	"com.infenia.yukta/go-cli/internal/commands/workflow/node"
	"github.com/spf13/cobra"
)

// WorkflowCmd creates and returns the "workflow" parent command.
func WorkflowCmd(c client.ClientInterface) *cobra.Command {
	cmd := &cobra.Command{
		Use:   "workflow",
		Short: "Manage workflow executions",
		Long: `Manage workflow executions, including starting, stopping, pausing, resuming,
and controlling individual nodes within a workflow.`,
	}

	cmd.AddCommand(StartCmd(c))
	cmd.AddCommand(StopCmd(c))
	cmd.AddCommand(PauseCmd(c))
	cmd.AddCommand(ResumeCmd(c))
	cmd.AddCommand(StatusCmd(c))
	cmd.AddCommand(StatusStreamCmd(c))
	cmd.AddCommand(HistoryCmd(c))
	cmd.AddCommand(executions.ExecutionsCmd(c))
	cmd.AddCommand(node.NodeCmd(c))

	return cmd
}
