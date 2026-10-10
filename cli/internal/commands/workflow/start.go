// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package workflow

import (
	"encoding/json"
	"fmt"
	"os"

	"com.infenia.yukta/go-cli/internal/client"
	"com.infenia.yukta/go-cli/internal/commands"
	"com.infenia.yukta/go-cli/internal/output"
	"github.com/spf13/cobra"
)

// StartCmd creates the "workflow start" command.
func StartCmd(c client.ClientInterface) *cobra.Command {
	var payload, payloadFile string
	cmd := &cobra.Command{
		Use:   "start <session-id> <workflow-id>",
		Short: "Start a workflow execution",
		Long: `Start executing a workflow in a given session.

Returns the execution ID of the started workflow. Use --payload (inline JSON
object) or --payload-file to pass input to the workflow's trigger node.`,
		Args: cobra.ExactArgs(2),
		RunE: func(cmd *cobra.Command, args []string) error {
			sessionID := args[0]
			workflowID := args[1]

			data, err := payloadBytes(payload, payloadFile)
			if err != nil {
				return err
			}
			var body map[string]any
			if data != nil {
				if err := json.Unmarshal(data, &body); err != nil {
					return fmt.Errorf("payload must be a JSON object: %w", err)
				}
			}

			resp, err := c.StartWorkflow(sessionID, workflowID, body)
			if err != nil {
				return err
			}

			format := commands.GetOutputFormat()
			if format == "json" {
				output.PrintJSON(resp)
				return nil
			}

			fmt.Printf("Workflow started with execution ID: %s\n", resp.ExecutionID)
			return nil
		},
	}
	cmd.Flags().StringVar(&payload, "payload", "", "Trigger payload as a JSON object")
	cmd.Flags().StringVar(&payloadFile, "payload-file", "", "File containing the trigger payload (JSON object)")
	cmd.MarkFlagsMutuallyExclusive("payload", "payload-file")
	return cmd
}

// payloadBytes returns the raw payload from the inline flag or file, or nil if neither is set.
func payloadBytes(inline, file string) ([]byte, error) {
	if file != "" {
		return os.ReadFile(file)
	}
	if inline != "" {
		return []byte(inline), nil
	}
	return nil, nil
}
