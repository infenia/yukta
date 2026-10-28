// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package session

import (
	"bufio"
	"fmt"
	"strings"

	"com.infenia.yukta/go-cli/internal/client"
	"github.com/spf13/cobra"
)

// DeleteCmd creates "session delete <session-id>".
func DeleteCmd(c *client.Client) *cobra.Command {
	return deleteCommand(
		"delete <session-id>",
		"Delete a session",
		"Delete a session with its workflows and their versions. Run history and logs stay. Active runs refuse the delete unless --force cancels them first. Needs the ADMIN role.",
		1,
		func(a []string) string { return fmt.Sprintf("session %s with all its workflows", a[0]) },
		func(a []string, force bool) error { return c.DeleteSession(a[0], force) },
	)
}

// WorkflowDeleteCmd creates "session workflow delete <session-id> <workflow-id>".
func WorkflowDeleteCmd(c *client.Client) *cobra.Command {
	return deleteCommand(
		"delete <session-id> <workflow-id>",
		"Delete a workflow",
		"Delete one workflow of a session with its versions. Its runs stay readable. Active runs refuse the delete unless --force cancels them first. Needs the ADMIN role.",
		2,
		func(a []string) string { return fmt.Sprintf("workflow %s of session %s", a[1], a[0]) },
		func(a []string, force bool) error { return c.DeleteWorkflow(a[0], a[1], force) },
	)
}

func deleteCommand(
	use, short, long string,
	args int,
	what func([]string) string,
	do func([]string, bool) error,
) *cobra.Command {
	var force, yes bool
	cmd := &cobra.Command{
		Use:   use,
		Short: short,
		Long:  long,
		Args:  cobra.ExactArgs(args),
		RunE: func(cmd *cobra.Command, a []string) error {
			if !yes {
				fmt.Fprintf(cmd.ErrOrStderr(), "Delete %s? This cannot be undone. [y/N] ", what(a))
				answer, _ := bufio.NewReader(cmd.InOrStdin()).ReadString('\n')
				if !strings.HasPrefix(strings.ToLower(strings.TrimSpace(answer)), "y") {
					return fmt.Errorf("not deleted")
				}
			}
			if err := do(a, force); err != nil {
				return fmt.Errorf("failed to delete: %w", err)
			}
			fmt.Fprintf(cmd.OutOrStdout(), "Deleted %s\n", what(a))
			return nil
		},
	}
	cmd.Flags().BoolVar(&force, "force", false, "Cancel active runs first instead of refusing")
	cmd.Flags().BoolVarP(&yes, "yes", "y", false, "Do not ask for confirmation")
	return cmd
}
