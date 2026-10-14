// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package controlbus

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/signal"
	"strings"
	"sync"
	"syscall"
	"time"

	"com.infenia.yukta/go-cli/internal/client"
	"com.infenia.yukta/go-cli/internal/commands"
	"github.com/spf13/cobra"
)

// snapshotInterval is how often --until-done checks the execution's status next to the stream.
// The server does not replay events, so a run that ended before (or while) we subscribed would
// otherwise never be reported.
var snapshotInterval = 3 * time.Second

// WatchCmd creates the "controlbus watch" command.
func WatchCmd(c *client.Client) *cobra.Command {
	var executionID, eventType string
	var untilDone, noLogs, quiet bool

	cmd := &cobra.Command{
		Use:   "watch",
		Short: "Stream control-bus events",
		Long: `Stream live control-bus events: node and workflow status changes, control command
outcomes and log lines, for all executions or one.

One line per event:
  NODE <node> <status>
  WORKFLOW <status>
  CONTROL <command> applied=<bool> [reason]
  LOG <node>: <line>
With --quiet only problems and the end are shown: failed or otherwise non-SUCCESS final node
statuses, control commands that were not applied, and the final workflow status.
With -o json, each event is printed as one JSON object per line.

The server does not replay events: only what happens after the stream opens is shown.
With --until-done (needs --execution) the command also checks the execution's status while it
waits, and exits once the execution has ended, including when it ended before the watch began.
Press Ctrl-C to stop watching at any time.`,
		Args: cobra.NoArgs,
		RunE: func(cmd *cobra.Command, _ []string) error {
			if untilDone && executionID == "" {
				return errors.New("--until-done needs --execution")
			}

			baseCtx := cmd.Context()
			if baseCtx == nil {
				baseCtx = context.Background()
			}
			sigCtx, stopSignals := signal.NotifyContext(baseCtx, os.Interrupt, syscall.SIGTERM)
			defer stopSignals()
			ctx, cancel := context.WithCancel(sigCtx)
			defer cancel()

			w := &watcher{
				cancel:    cancel,
				eventType: eventType,
				untilDone: untilDone,
				noLogs:    noLogs,
				quiet:     quiet,
				json:      commands.GetOutputFormat() == "json",
			}
			if untilDone {
				go w.pollStatus(ctx, c, executionID)
			}

			err := c.StreamEvents(ctx, executionID, eventType, w.handle)
			if errors.Is(err, context.Canceled) {
				return nil
			}
			return err
		},
	}

	cmd.Flags().StringVar(&executionID, "execution", "", "Only events of this execution")
	cmd.Flags().StringVar(&eventType, "type", "",
		"Only events of this kind: NodeStatusChanged, WorkflowStatusChanged, ControlOutcome or LogLine")
	cmd.Flags().BoolVar(&untilDone, "until-done", false, "Exit when the execution has ended (needs --execution)")
	cmd.Flags().BoolVar(&noLogs, "no-logs", false, "Leave out log lines")
	cmd.Flags().BoolVar(&quiet, "quiet", false, "Only problems and the final workflow status")
	return cmd
}

// watcher prints events and decides when --until-done is satisfied. Events arrive from the stream
// and from the status poll, so handle is serialized.
type watcher struct {
	mu        sync.Mutex
	finished  bool
	cancel    context.CancelFunc
	eventType string
	untilDone bool
	noLogs    bool
	quiet     bool
	json      bool
	seen      map[nodeKey]string // last status printed per execution node, to drop snapshot/stream duplicates
}

// nodeKey identifies one node of one execution; without the execution, an unscoped watch would drop
// the same node's status from a different execution.
type nodeKey struct {
	execution string
	node      string
}

func (w *watcher) handle(e client.ExecutionEvent) error {
	w.mu.Lock()
	defer w.mu.Unlock()
	if w.finished {
		return nil
	}
	if e.Type == client.EventNodeStatusChanged {
		key := nodeKey{execution: e.ExecutionID, node: e.NodeID}
		if w.seen[key] == e.Status {
			return nil
		}
		if w.seen == nil {
			w.seen = map[nodeKey]string{}
		}
		w.seen[key] = e.Status
	}
	if w.untilDone && e.Type == client.EventWorkflowStatusChanged && client.IsTerminalStatus(e.Status) {
		w.finished = true
		defer w.cancel()
	}
	if w.noLogs && e.Type == client.EventLogLine {
		return nil
	}
	if w.quiet && !isProblemOrEnd(e) {
		return nil
	}
	if w.eventType != "" && !strings.EqualFold(w.eventType, e.Type) {
		return nil
	}
	w.print(e)
	return nil
}

// isProblemOrEnd keeps what --quiet shows: the end of the workflow, final node statuses other than
// SUCCESS, and control commands that were not applied.
func isProblemOrEnd(e client.ExecutionEvent) bool {
	switch e.Type {
	case client.EventWorkflowStatusChanged:
		return client.IsTerminalStatus(e.Status)
	case client.EventNodeStatusChanged:
		return client.IsTerminalStatus(e.Status) && e.Status != "SUCCESS"
	case client.EventControlOutcome:
		return e.Applied == nil || !*e.Applied
	}
	return false
}

func (w *watcher) print(e client.ExecutionEvent) {
	if w.json {
		line, _ := json.Marshal(e)
		fmt.Println(string(line))
		return
	}
	fmt.Println(formatEvent(e))
}

// pollStatus turns a snapshot into events: the final status of nodes that already ended (their
// events were missed when the watch attached late) and, once it ended, the workflow status.
func (w *watcher) pollStatus(ctx context.Context, c *client.Client, executionID string) {
	ticker := time.NewTicker(snapshotInterval)
	defer ticker.Stop()
	for {
		progress, err := c.GetExecution(executionID)
		if err == nil {
			for _, t := range progress.Tasks {
				if client.IsTerminalStatus(t.Status) {
					_ = w.handle(client.ExecutionEvent{
						Type:        client.EventNodeStatusChanged,
						ExecutionID: executionID,
						NodeID:      t.NodeID,
						Status:      t.Status,
					})
				}
			}
		}
		if err == nil && client.IsTerminalStatus(progress.Status) {
			_ = w.handle(client.ExecutionEvent{
				Type:        client.EventWorkflowStatusChanged,
				ExecutionID: executionID,
				WorkflowID:  progress.WorkflowID,
				Status:      progress.Status,
			})
			return
		}
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
		}
	}
}

func formatEvent(e client.ExecutionEvent) string {
	switch e.Type {
	case client.EventNodeStatusChanged:
		return fmt.Sprintf("NODE %s %s", e.NodeID, e.Status)
	case client.EventWorkflowStatusChanged:
		return "WORKFLOW " + e.Status
	case client.EventControlOutcome:
		applied := e.Applied != nil && *e.Applied
		line := fmt.Sprintf("CONTROL %s applied=%t", e.Command, applied)
		if e.Reason != "" {
			line += " " + e.Reason
		}
		return line
	case client.EventLogLine:
		return fmt.Sprintf("LOG %s: %s", e.NodeID, e.Line)
	default:
		return strings.ToUpper(e.Type)
	}
}
