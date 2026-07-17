// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package step

import (
	"com.infenia.yukta/go-cli/internal/client"
	"github.com/spf13/cobra"
)

// StepCmd creates and returns the "workflow node step" parent command.
func StepCmd(c client.ClientInterface) *cobra.Command {
	cmd := &cobra.Command{
		Use:   "step",
		Short: "Control step-through debugging of nodes",
		Long: `Control step-through debugging for individual nodes in a workflow execution.

Step mode allows you to pause the workflow and execute one node at a time.`,
	}

	cmd.AddCommand(EnableCmd(c))
	cmd.AddCommand(DisableCmd(c))
	cmd.AddCommand(NextCmd(c))

	return cmd
}
