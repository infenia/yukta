// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package step

import (
	"fmt"

	"com.infenia.yukta/go-cli/internal/client"
	"com.infenia.yukta/go-cli/internal/commands"
	"com.infenia.yukta/go-cli/internal/output"
	"github.com/spf13/cobra"
)

// NextCmd creates the "workflow node step next" command.
func NextCmd(c client.ClientInterface) *cobra.Command {
	return &cobra.Command{
		Use:   "next <session-id> <execution-id> <node-id>",
		Short: "Step to the next execution in a node",
		Long: `Execute the next step in a node that is in step-through debug mode.

This command is used when a node has step mode enabled to progress execution one step at a time.`,
		Args: cobra.ExactArgs(3),
		RunE: func(cmd *cobra.Command, args []string) error {
			sessionID := args[0]
			executionID := args[1]
			nodeID := args[2]

			resp, err := c.StepNode(sessionID, executionID, nodeID)
			if err != nil {
				return err
			}

			format := commands.GetOutputFormat()
			if format == "json" {
				output.PrintJSON(resp)
				return nil
			}

			fmt.Printf("Stepped to next execution in node: %s\n", nodeID)
			return nil
		},
	}
}
