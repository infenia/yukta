// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package whoami

import (
	"fmt"
	"sort"
	"strings"

	"com.infenia.yukta/go-cli/internal/client"
	"com.infenia.yukta/go-cli/internal/commands"
	"com.infenia.yukta/go-cli/internal/output"
	"github.com/spf13/cobra"
)

// WhoamiCmd creates the "whoami" command: the key's name, role and what it may do.
func WhoamiCmd(c *client.Client) *cobra.Command {
	return &cobra.Command{
		Use:   "whoami",
		Short: "Show who the server thinks you are",
		Long:  "Show the name, role and sessions of the API key in use and what it may do. With no key configured on the server the role is ADMIN.",
		Args:  cobra.NoArgs,
		RunE: func(cmd *cobra.Command, args []string) error {
			who, err := c.GetWhoami()
			if err != nil {
				return fmt.Errorf("failed to get the identity: %w", err)
			}
			if commands.GetOutputFormat() == "json" {
				output.PrintJSON(who)
				return nil
			}
			output.PrintTable([]string{"Field", "Value"}, rows(who))
			return nil
		},
	}
}

func rows(who client.Whoami) [][]string {
	name := "-"
	if who.KeyName != nil {
		name = *who.KeyName
	}
	sessions := "all"
	if who.Sessions != nil {
		sessions = strings.Join(who.Sessions, ", ")
	}
	out := [][]string{
		{"key", name},
		{"role", who.Role},
		{"sessions", sessions},
		{"authEnabled", fmt.Sprint(who.AuthEnabled)},
	}
	names := make([]string, 0, len(who.Permissions))
	for permission := range who.Permissions {
		names = append(names, permission)
	}
	sort.Strings(names)
	for _, permission := range names {
		out = append(out, []string{permission, fmt.Sprint(who.Permissions[permission])})
	}
	return out
}
