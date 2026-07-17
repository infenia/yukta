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

// StopCmd creates the "workflow node stop" command.
func StopCmd(c client.ClientInterface) *cobra.Command {
	var immediate bool
	var reason string

	cmd := &cobra.Command{
		Use:   "stop <session-id> <execution-id> <node-id>",
		Short: "Stop a node in a workflow execution",
		Long: `Stop execution of a specific node within a workflow.

The node will be terminated immediately or gracefully depending on the --immediate flag.
Optionally provide a reason for the stop using --reason.`,
		Args: cobra.ExactArgs(3),
		RunE: func(cmd *cobra.Command, args []string) error {
			sessionID := args[0]
			executionID := args[1]
			nodeID := args[2]

			resp, err := c.StopNode(sessionID, executionID, nodeID, immediate, reason)
			if err != nil {
				return err
			}

			format := commands.GetOutputFormat()
			if format == "json" {
				output.PrintJSON(resp)
				return nil
			}

			fmt.Printf("Node stopped: %s\n", nodeID)
			return nil
		},
	}

	cmd.Flags().BoolVar(&immediate, "immediate", false, "Stop the node immediately without graceful shutdown")
	cmd.Flags().StringVar(&reason, "reason", "", "Reason for stopping the node")

	return cmd
}
