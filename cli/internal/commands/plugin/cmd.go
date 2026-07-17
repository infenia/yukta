// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package plugin

import (
	"com.infenia.yukta/go-cli/internal/client"
	"github.com/spf13/cobra"
)

// PluginCmd creates and returns the "plugin" command group.
// It serves as the parent command for plugin-related subcommands.
func PluginCmd(c client.ClientInterface) *cobra.Command {
	cmd := &cobra.Command{
		Use:   "plugin",
		Short: "Manage and discover plugins",
		Long:  "Commands for discovering and managing workflow plugins.",
	}

	cmd.AddCommand(ListCmd(c))
	cmd.AddCommand(GetCmd(c))

	return cmd
}
