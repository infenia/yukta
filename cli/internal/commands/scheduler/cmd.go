// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package scheduler

import (
	"fmt"
	"strconv"

	"com.infenia.yukta/go-cli/internal/client"
	"com.infenia.yukta/go-cli/internal/commands"
	"com.infenia.yukta/go-cli/internal/output"
	"github.com/spf13/cobra"
)

// SchedulerCmd creates the "scheduler" command group.
func SchedulerCmd(c *client.Client) *cobra.Command {
	cmd := &cobra.Command{
		Use:   "scheduler",
		Short: "Execution limits",
		Long:  "Show and change how many executions may run at once.",
	}
	cmd.AddCommand(LimitsCmd(c))
	return cmd
}

// LimitsCmd creates "scheduler limits" (show) with its "set" subcommand.
func LimitsCmd(c *client.Client) *cobra.Command {
	cmd := &cobra.Command{
		Use:   "limits",
		Short: "Show the execution limits",
		Long:  "Show the limits in force (0 is unlimited) and how many executions run and wait now.",
		Args:  cobra.NoArgs,
		RunE: func(cmd *cobra.Command, args []string) error {
			limits, err := c.GetSchedulerLimits()
			if err != nil {
				return fmt.Errorf("failed to get the limits: %w", err)
			}
			show(limits)
			return nil
		},
	}
	cmd.AddCommand(setCmd(c))
	return cmd
}

func setCmd(c *client.Client) *cobra.Command {
	var maxConcurrent, maxPerWorkflow int
	cmd := &cobra.Command{
		Use:   "set --max-concurrent N --max-per-workflow M",
		Short: "Change the execution limits",
		Long:  "Change how many executions may run at once, in total and per workflow (0 is unlimited). Raising a limit admits waiting starts at once. Needs an admin key.",
		Args:  cobra.NoArgs,
		RunE: func(cmd *cobra.Command, args []string) error {
			limits, err := c.SetSchedulerLimits(maxConcurrent, maxPerWorkflow)
			if err != nil {
				return fmt.Errorf("failed to set the limits: %w", err)
			}
			show(limits)
			return nil
		},
	}
	cmd.Flags().IntVar(&maxConcurrent, "max-concurrent", 0, "Most executions running at once (0 for unlimited)")
	cmd.Flags().IntVar(&maxPerWorkflow, "max-per-workflow", 0, "Most executions of one workflow running at once (0 for unlimited)")
	_ = cmd.MarkFlagRequired("max-concurrent")
	_ = cmd.MarkFlagRequired("max-per-workflow")
	return cmd
}

func show(l client.SchedulerLimits) {
	if commands.GetOutputFormat() == "json" {
		output.PrintJSON(l)
		return
	}
	output.PrintTable([]string{"Field", "Value"}, [][]string{
		{"maxConcurrent", strconv.Itoa(l.MaxConcurrent)},
		{"maxPerWorkflow", strconv.Itoa(l.MaxPerWorkflow)},
		{"running", strconv.Itoa(l.Running)},
		{"queued", strconv.Itoa(l.Queued)},
		{"queueCapacity", strconv.Itoa(l.QueueCapacity)},
	})
}
