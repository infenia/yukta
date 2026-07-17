// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package controlbus

import (
	"com.infenia.yukta/go-cli/internal/client"
	"github.com/spf13/cobra"
)

// ControlBusCmd creates and returns the "controlbus" command group.
// It serves as the parent command for control bus operations.
func ControlBusCmd(c client.ClientInterface) *cobra.Command {
	cmd := &cobra.Command{
		Use:   "controlbus",
		Short: "Control bus and observability operations",
		Long:  "Commands for querying and controlling workflow nodes via the control bus.",
	}

	cmd.AddCommand(NodesCmd(c))
	cmd.AddCommand(AllNodesCmd(c))
	cmd.AddCommand(HeartbeatCmd(c))
	cmd.AddCommand(CommandCmd(c))

	return cmd
}
