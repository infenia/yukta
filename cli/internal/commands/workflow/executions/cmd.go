// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package executions

import (
	"com.infenia.yukta/go-cli/internal/client"
	"github.com/spf13/cobra"
)

// ExecutionsCmd creates and returns the "workflow executions" parent command.
func ExecutionsCmd(c client.ClientInterface) *cobra.Command {
	cmd := &cobra.Command{
		Use:   "executions",
		Short: "Manage workflow executions",
		Long:  `Manage individual workflow executions, including stopping and restarting them.`,
	}

	cmd.AddCommand(StopCmd(c))
	cmd.AddCommand(RestartCmd(c))

	return cmd
}
