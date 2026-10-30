// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package controlbus

import (
	"com.infenia.yukta/go-cli/internal/client"
	"com.infenia.yukta/go-cli/internal/commands"
	"github.com/spf13/cobra"
)

// ControlBusCmd creates and returns the "controlbus" command group.
// It serves as the parent command for control bus operations.
func ControlBusCmd(c *client.Client) *cobra.Command {
	cmd := &cobra.Command{
		Use:   "controlbus",
		Short: "Control bus and observability operations",
		Long:  "Commands for querying and controlling workflow nodes via the control bus.",
	}

	cmd.AddCommand(NodesCmd(c))
	cmd.AddCommand(AllNodesCmd(c))
	cmd.AddCommand(HeartbeatCmd(c))
	cmd.AddCommand(CommandCmd(c))
	cmd.AddCommand(WatchCmd(c))
	var limit int
	var execution string
	audit := commands.DataCmd("audit", "List control commands sent, newest first", 0,
		func([]string) (any, error) { return c.GetControlAudit(limit, execution) })
	audit.Flags().IntVar(&limit, "limit", 0, "At most this many entries (default: the server's)")
	audit.Flags().StringVar(&execution, "execution", "", "Only commands to this execution")
	cmd.AddCommand(audit)

	return cmd
}
