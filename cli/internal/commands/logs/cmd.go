// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package logs

import (
	"context"
	"errors"
	"fmt"
	"os"
	"os/signal"
	"syscall"

	"com.infenia.yukta/go-cli/internal/client"
	"github.com/spf13/cobra"
)

// LogsCmd creates and returns the "logs" command.
// It streams execution logs for a given session and execution ID.
// The command prints logs in real-time, one per line, as they arrive from the server.
// It supports graceful cancellation via Ctrl-C.
func LogsCmd(c client.ClientInterface) *cobra.Command {
	return &cobra.Command{
		Use:   "logs <session-id> <execution-id>",
		Short: "Stream execution logs",
		Long: `Stream execution logs for a workflow execution in real-time.

The logs are displayed as they arrive from the server, one line per log entry.
The stream continues until the execution reaches a terminal state (completed, failed, cancelled, etc).

Press Ctrl-C to stop watching logs at any time.`,
		Args: cobra.ExactArgs(2),
		RunE: func(cmd *cobra.Command, args []string) error {
			sessionID := args[0]
			executionID := args[1]

			// Create a context that can be cancelled by signal handlers
			baseCtx := cmd.Context()
			if baseCtx == nil {
				baseCtx = context.Background()
			}

			ctx, stop := signal.NotifyContext(baseCtx, os.Interrupt, syscall.SIGTERM)
			defer stop()

			// Stream logs, printing each line as it arrives
			err := c.StreamExecutionLogs(ctx, sessionID, executionID, func(line string) error {
				fmt.Println(line)
				return nil
			})

			// If context was cancelled (Ctrl-C), treat it as a clean exit
			if errors.Is(err, context.Canceled) {
				return nil
			}

			return err
		},
	}
}
