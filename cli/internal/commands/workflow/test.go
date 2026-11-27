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

// TestCmd creates the "workflow test" command.
func TestCmd(c *client.Client) *cobra.Command {
	return &cobra.Command{
		Use:   "test <session-id> <test-file>",
		Short: "Test a saved workflow with stubbed nodes and assertions",
		Long: `Run a saved workflow once without its side effects and check what it did.

The test file is a JSON object: "workflowId" names the workflow, and the rest is the test:

  { "workflowId": "deploy", "input": {"env": "prod"},
    "stubs": { "build": [{"payload": {"exitCode": 0}, "port": "success"}] },
    "expect": { "reached": ["notify"], "notReached": ["rollback"],
                "nodes": { "notify": {"payload.status": "ok"} }, "status": "SUCCESS" } }

Triggers emit "input" (or their stub), terminals do nothing, and a processor with a stub emits
it instead of running. Any other processor runs for real, so stub every node with a side effect
or list the ones that may run in "allow". Prints one line per assertion and exits 1 when one
fails, so it fits a CI step.`,
		Args: cobra.ExactArgs(2),
		RunE: func(_ *cobra.Command, args []string) error {
			data, err := os.ReadFile(args[1]) // #nosec G304 -- the user names this file on purpose
			if err != nil {
				return err
			}
			var spec map[string]any
			if err := json.Unmarshal(data, &spec); err != nil {
				return fmt.Errorf("the test file must be a JSON object: %w", err)
			}
			workflowID, _ := spec["workflowId"].(string)
			if workflowID == "" {
				return fmt.Errorf("the test file must name its workflow in \"workflowId\"")
			}
			delete(spec, "workflowId")

			report, err := c.TestWorkflow(args[0], workflowID, spec)
			if err != nil {
				return err
			}
			if commands.GetOutputFormat() == "json" {
				output.PrintJSON(report)
			} else {
				printReport(workflowID, report)
			}
			if !report.Passed {
				return fmt.Errorf("workflow test of %s failed", workflowID)
			}
			return nil
		},
	}
}

// printReport writes one line per assertion and a verdict.
func printReport(workflowID string, report client.TestReport) {
	failed := 0
	for _, check := range report.Assertions {
		mark := "PASS"
		if !check.Passed {
			mark = "FAIL"
			failed++
		}
		line := fmt.Sprintf("%s  %s", mark, check.Name)
		if check.Detail != "" {
			line += ": " + check.Detail
		}
		fmt.Println(line)
	}
	verdict := "PASSED"
	if !report.Passed {
		verdict = "FAILED"
	}
	fmt.Printf("%s %s (run %s, %d of %d assertions failed)\n",
		verdict, workflowID, report.Status, failed, len(report.Assertions))
}
