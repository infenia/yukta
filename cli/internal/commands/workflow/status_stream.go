// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package workflow

import (
	"context"
	"errors"
	"os"
	"os/signal"
	"syscall"

	"com.infenia.yukta/go-cli/internal/client"
	"com.infenia.yukta/go-cli/internal/output"
	"github.com/spf13/cobra"
)

// StatusStreamCmd creates the "workflow status-stream" command.
func StatusStreamCmd(c client.ClientInterface) *cobra.Command {
	var includeHistory bool

	cmd := &cobra.Command{
		Use:   "status-stream <session-id> <execution-id>",
		Short: "Stream the status of a workflow execution",
		Long: `Stream the progress of a workflow execution in real-time via SSE.

The status updates are displayed as they arrive from the server.
Press Ctrl-C to stop watching status at any time.`,
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

			// Stream status updates
			err := c.StreamWorkflowStatus(ctx, sessionID, executionID, includeHistory, func(progress client.WorkflowProgress) error {
				output.PrintJSON(progress)
				return nil
			})

			// If context was cancelled (Ctrl-C), treat it as a clean exit
			if errors.Is(err, context.Canceled) {
				return nil
			}

			return err
		},
	}

	cmd.Flags().BoolVar(&includeHistory, "include-history", false, "Include execution history in stream")

	return cmd
}
