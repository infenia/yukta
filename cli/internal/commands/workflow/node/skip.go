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

// SkipCmd creates the "workflow node skip" command.
func SkipCmd(c client.ClientInterface) *cobra.Command {
	var skip bool

	cmd := &cobra.Command{
		Use:   "skip <session-id> <execution-id> <node-id>",
		Short: "Skip a node in a workflow execution",
		Long: `Mark a node as skipped or unskipped in a workflow execution.

By default, marks the node as skipped. Use --skip=false to unskip the node.`,
		Args: cobra.ExactArgs(3),
		RunE: func(cmd *cobra.Command, args []string) error {
			sessionID := args[0]
			executionID := args[1]
			nodeID := args[2]

			resp, err := c.SkipNode(sessionID, executionID, nodeID, skip)
			if err != nil {
				return err
			}

			format := commands.GetOutputFormat()
			if format == "json" {
				output.PrintJSON(resp)
				return nil
			}

			action := "skipped"
			if !skip {
				action = "unskipped"
			}
			fmt.Printf("Node %s: %s\n", nodeID, action)
			return nil
		},
	}

	cmd.Flags().BoolVar(&skip, "skip", true, "Whether to skip the node (default true)")

	return cmd
}
