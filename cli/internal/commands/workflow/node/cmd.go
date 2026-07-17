// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package node

import (
	"com.infenia.yukta/go-cli/internal/client"
	"com.infenia.yukta/go-cli/internal/commands/workflow/node/step"
	"github.com/spf13/cobra"
)

// NodeCmd creates and returns the "workflow node" parent command.
func NodeCmd(c client.ClientInterface) *cobra.Command {
	cmd := &cobra.Command{
		Use:   "node",
		Short: "Control individual nodes in a workflow",
		Long: `Control individual nodes within a workflow execution,
including pausing, resuming, stopping, skipping, and step-through debugging.`,
	}

	cmd.AddCommand(PauseCmd(c))
	cmd.AddCommand(ResumeCmd(c))
	cmd.AddCommand(StopCmd(c))
	cmd.AddCommand(SkipCmd(c))
	cmd.AddCommand(step.StepCmd(c))

	return cmd
}
