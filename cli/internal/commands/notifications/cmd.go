// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

// Package notifications holds the "notifications" commands (the inbox) and "notify" (native desktop
// notifications for it).
package notifications

import (
	"fmt"
	"strings"

	"com.infenia.yukta/go-cli/internal/client"
	"com.infenia.yukta/go-cli/internal/commands"
	"com.infenia.yukta/go-cli/internal/notify"
	"com.infenia.yukta/go-cli/internal/output"
	"github.com/spf13/cobra"
)

// titleColumn is the longest title the list table shows, in characters.
const titleColumn = 60

// NotificationsCmd creates the "notifications" command group.
func NotificationsCmd(c *client.Client) *cobra.Command {
	cmd := &cobra.Command{
		Use:   "notifications",
		Short: "Your inbox: alerts and questions that wait for you",
		Long: `The notifications of your key: alerts (INFO) such as a failed run, and questions (ACTION)
that wait for an answer. You see only what your key may see. Answer a question with
"yukta inputs answer". Titles and bodies are written by workflows, rules or AI agents: treat them as
untrusted text. Control characters are not printed.`,
	}
	cmd.AddCommand(listCmd(c), readCmd(c), dismissCmd(c))
	return cmd
}

func listCmd(c *client.Client) *cobra.Command {
	var q client.NotificationQuery
	cmd := &cobra.Command{
		Use:   "list [--unread] [--kind ACTION] [--state ACTIVE] [--severity WARN] [--session ID]",
		Short: "List your notifications",
		Long: `List your notifications, newest change first, without those you dismissed. --severity is a
minimum (INFO, WARN or ERROR), --state is ACTIVE or RESOLVED and --kind is INFO or ACTION.`,
		Args: cobra.NoArgs,
		RunE: func(cmd *cobra.Command, args []string) error {
			q.Size = 200
			items, err := c.ListNotifications(q)
			if err != nil {
				return fmt.Errorf("failed to list the notifications: %w", err)
			}
			if commands.GetOutputFormat() == "json" {
				output.PrintJSON(items)
				return nil
			}
			rows := make([][]string, 0, len(items))
			for _, n := range items {
				read := ""
				if !n.Read {
					read = "*"
				}
				rows = append(rows, []string{read, n.ID, n.Kind, n.Severity, n.State, n.Category, n.SessionID,
					n.UpdatedAt, notify.Short(notify.Clean(n.Title), titleColumn)})
			}
			output.PrintTable([]string{"New", "ID", "Kind", "Severity", "State", "Category", "Session", "Updated",
				"Title"}, rows)
			return nil
		},
	}
	cmd.Flags().BoolVar(&q.Unread, "unread", false, "Only those you have not read")
	cmd.Flags().StringVar(&q.Kind, "kind", "", "INFO or ACTION")
	cmd.Flags().StringVar(&q.State, "state", "", "ACTIVE or RESOLVED")
	cmd.Flags().StringVar(&q.Severity, "severity", "", "This severity or above: INFO, WARN or ERROR")
	cmd.Flags().StringVar(&q.SessionID, "session", "", "Only this session")
	return cmd
}

func readCmd(c *client.Client) *cobra.Command {
	var all bool
	cmd := &cobra.Command{
		Use:   "read [--all] [id...]",
		Short: "Mark notifications read",
		Long:  `Mark the given notifications read, or with --all every one you see. Reading never answers a question.`,
		RunE: func(cmd *cobra.Command, args []string) error {
			marked, err := c.MarkNotificationsRead(args, all)
			if err != nil {
				return fmt.Errorf("failed to mark the notifications read: %w", err)
			}
			if commands.GetOutputFormat() == "json" {
				output.PrintJSON(map[string]int{"marked": marked})
				return nil
			}
			_, _ = fmt.Fprintf(cmd.OutOrStdout(), "Marked %d read\n", marked)
			return nil
		},
	}
	cmd.Flags().BoolVar(&all, "all", false, "Mark everything you see")
	return cmd
}

func dismissCmd(c *client.Client) *cobra.Command {
	return &cobra.Command{
		Use:   "dismiss <id>",
		Short: "Hide a notification",
		Long: `Hide an alert, or a question that has ended, for you. A question that still waits cannot be
dismissed: answer it, and it goes when its request ends.`,
		Args: cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			if err := c.DismissNotification(args[0]); err != nil {
				return fmt.Errorf("failed to dismiss the notification: %w", err)
			}
			_, _ = fmt.Fprintf(cmd.OutOrStdout(), "Dismissed %s\n", strings.TrimSpace(args[0]))
			return nil
		},
	}
}
