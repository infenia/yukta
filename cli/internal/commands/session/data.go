// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package session

import (
	"com.infenia.yukta/go-cli/internal/output"
	"github.com/spf13/cobra"
)

// dataCmd builds a read-only command that fetches nested data and prints it as JSON
// (nested structures don't fit a table, so -o has no effect).
func dataCmd(use, short string, nargs int, fetch func(args []string) (any, error)) *cobra.Command {
	return &cobra.Command{
		Use:   use,
		Short: short,
		Args:  cobra.ExactArgs(nargs),
		RunE: func(_ *cobra.Command, args []string) error {
			data, err := fetch(args)
			if err != nil {
				return err
			}
			output.PrintJSON(data)
			return nil
		},
	}
}
