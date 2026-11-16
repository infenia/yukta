// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

// Package ptyhost holds the hidden "pty-host" command: the Yukta server starts it to run an agent in
// a pseudo-terminal and talks to it in frames on standard input and output.
package ptyhost

import (
	"errors"
	"fmt"

	"com.infenia.yukta/go-cli/internal/ptyhost"
	"github.com/spf13/cobra"
)

// ExitError carries the exit code of the program the host ran: the host exits with it and prints
// nothing, because standard output belongs to the frames.
type ExitError struct {
	Code int
}

func (e *ExitError) Error() string { return fmt.Sprintf("the program exited with code %d", e.Code) }

// PtyHostCmd creates the hidden "pty-host" command.
func PtyHostCmd() *cobra.Command {
	var cols, rows int
	cmd := &cobra.Command{
		Use:    "pty-host --cols C --rows R -- <command> [args...]",
		Short:  "Run a program in a pseudo-terminal and speak frames (started by the server)",
		Hidden: true,
		RunE: func(cmd *cobra.Command, args []string) error {
			if cmd.ArgsLenAtDash() != 0 || len(args) == 0 {
				return errors.New("give the program after --")
			}
			code, err := ptyhost.Run(cmd.InOrStdin(), cmd.OutOrStdout(), ptyhost.New(args, cols, rows))
			if err != nil {
				return err
			}
			if code != 0 {
				return &ExitError{Code: code}
			}
			return nil
		},
	}
	cmd.Flags().IntVar(&cols, "cols", 120, "Width of the terminal")
	cmd.Flags().IntVar(&rows, "rows", 40, "Height of the terminal")
	return cmd
}
