// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package approvals

import (
	"fmt"

	"com.infenia.yukta/go-cli/internal/client"
	"com.infenia.yukta/go-cli/internal/commands"
	"com.infenia.yukta/go-cli/internal/output"
	"github.com/spf13/cobra"
)

// ApprovalsCmd creates the "approvals" command group.
func ApprovalsCmd(c *client.Client) *cobra.Command {
	cmd := &cobra.Command{
		Use:   "approvals",
		Short: "Nodes that wait for a decision",
	}
	cmd.AddCommand(listCmd(c))
	return cmd
}

func listCmd(c *client.Client) *cobra.Command {
	var session string
	cmd := &cobra.Command{
		Use:   "list [--session ID]",
		Short: "List pending approvals",
		Long:  "List the nodes that wait for a signal now, oldest first: in all sessions you may see, or in one with --session.",
		Args:  cobra.NoArgs,
		RunE: func(cmd *cobra.Command, args []string) error {
			approvals, err := c.ListApprovals(session)
			if err != nil {
				return fmt.Errorf("failed to list the approvals: %w", err)
			}
			if commands.GetOutputFormat() == "json" {
				output.PrintJSON(approvals)
				return nil
			}
			rows := make([][]string, 0, len(approvals))
			for _, a := range approvals {
				rows = append(rows, []string{a.SessionID, a.WorkflowID, a.ExecutionID, a.NodeID, a.Name, a.Since})
			}
			output.PrintTable([]string{"Session", "Workflow", "Execution", "Node", "Signal", "Since"}, rows)
			return nil
		},
	}
	cmd.Flags().StringVar(&session, "session", "", "Only this session")
	return cmd
}
