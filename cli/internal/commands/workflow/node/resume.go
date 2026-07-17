// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package node

import (
	"fmt"

	"com.infenia.yukta/go-cli/internal/client"
	"com.infenia.yukta/go-cli/internal/commands"
	"com.infenia.yukta/go-cli/internal/output"
	"github.com/spf13/cobra"
)

// ResumeCmd creates the "workflow node resume" command.
func ResumeCmd(c client.ClientInterface) *cobra.Command {
	return &cobra.Command{
		Use:   "resume <session-id> <execution-id> <node-id>",
		Short: "Resume a paused node in a workflow execution",
		Long: `Resume execution of a paused node within a workflow.

The node will continue executing from where it was paused.`,
		Args: cobra.ExactArgs(3),
		RunE: func(cmd *cobra.Command, args []string) error {
			sessionID := args[0]
			executionID := args[1]
			nodeID := args[2]

			resp, err := c.ResumeNode(sessionID, executionID, nodeID)
			if err != nil {
				return err
			}

			format := commands.GetOutputFormat()
			if format == "json" {
				output.PrintJSON(resp)
				return nil
			}

			fmt.Printf("Node resumed: %s\n", nodeID)
			return nil
		},
	}
}
