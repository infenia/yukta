// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package result

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

// ResultCmd creates and returns the "result" command.
// It follows what reaches the terminal nodes of an execution and prints each event as
// one line of JSON as it arrives. It supports graceful cancellation via Ctrl-C.
func ResultCmd(c *client.Client) *cobra.Command {
	var node string
	cmd := &cobra.Command{
		Use:   "result <session-id> <execution-id>",
		Short: "Stream the result of an execution",
		Long: `Stream the result of a workflow execution as it is produced.

Each line is a JSON event: RESULT (a message reached a terminal node, with its payload),
NODE_END (a terminal node finished) and EXECUTION_END (the execution finished; the command
then exits). Events already produced are replayed first, so the command also works after
the execution has ended, for as long as the server keeps the result.

Use --node to follow a single terminal node.

Press Ctrl-C to stop at any time.`,
		Args: cobra.ExactArgs(2),
		RunE: func(cmd *cobra.Command, args []string) error {
			baseCtx := cmd.Context()
			if baseCtx == nil {
				baseCtx = context.Background()
			}
			ctx, stop := signal.NotifyContext(baseCtx, os.Interrupt, syscall.SIGTERM)
			defer stop()

			err := c.StreamExecutionResults(ctx, args[0], args[1], node, func(_ string, data string) error {
				fmt.Println(data)
				return nil
			})
			if errors.Is(err, context.Canceled) {
				return nil
			}
			return err
		},
	}
	cmd.Flags().StringVar(&node, "node", "", "Follow only this terminal node")
	return cmd
}
