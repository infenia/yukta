// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package commands

import (
	"fmt"

	"com.infenia.yukta/go-cli/internal/output"
	"github.com/spf13/cobra"
)

// ActionSpec describes a command that performs one server call and reports the result.
type ActionSpec[R any] struct {
	Use, Short, Long string
	Args             cobra.PositionalArgs
	// Run performs the call using the positional arguments.
	Run func(args []string) (R, error)
	// Msg renders the response for humans; with -o json the response is printed as JSON instead.
	Msg func(resp R, args []string) string
}

// Action builds a command from spec. Callers may add flags to the result; Run closures can read them.
func Action[R any](spec ActionSpec[R]) *cobra.Command {
	return &cobra.Command{
		Use:   spec.Use,
		Short: spec.Short,
		Long:  spec.Long,
		Args:  spec.Args,
		RunE: func(_ *cobra.Command, args []string) error {
			resp, err := spec.Run(args)
			if err != nil {
				return err
			}
			if GetOutputFormat() == "json" {
				output.PrintJSON(resp)
				return nil
			}
			fmt.Println(spec.Msg(resp, args))
			return nil
		},
	}
}
