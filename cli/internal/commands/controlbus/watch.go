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

// successorWait is how long --follow-restarts looks for the run that restarts the one that ended,
// and successorPoll how often.
var (
	successorWait = 10 * time.Second
	successorPoll = time.Second
)

// WatchCmd creates the "controlbus watch" command.
func WatchCmd(c *client.Client) *cobra.Command {
	var executionID, eventType string
	var untilDone, noLogs, quiet, tree, followRestarts bool

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
With --quiet only problems and the end are shown, which keeps a long run cheap to follow:
failed or otherwise non-SUCCESS final node statuses, each followed by the reason the node
reports, the error and warning lines of the output as
  ERROR <node>: <line>
  WARN <node>: <line>
(each once, at most 20 per node), control commands that were not applied, and the final
workflow status.
With -o json, each event is printed as one JSON object per line.

With --execution the runs it starts as sub-workflows are watched too (--tree, the default): their
lines name the child workflow, as in "NODE check/style FAILURE" or "WORKFLOW check FAILURE".

The server does not replay events: only what happens after the stream opens is shown.
With --until-done (needs --execution) the command also checks the execution's status while it
waits, and exits once the execution itself has ended, including when it ended before the watch
began. With --follow-restarts it then moves on to the run that restarted it, if one starts within
a few seconds, and prints "RESTARTED <old> <new>".
Press Ctrl-C to stop watching at any time.`,
		Args: cobra.NoArgs,
		RunE: func(cmd *cobra.Command, _ []string) error {
			if strings.HasPrefix(executionID, "-") {
				return fmt.Errorf("--execution needs an execution id, not %q", executionID)
			}
			if untilDone && executionID == "" {
				return errors.New("--until-done needs --execution")
			}
			if followRestarts && !untilDone {
				return errors.New("--follow-restarts needs --until-done")
			}

			baseCtx := cmd.Context()
			if baseCtx == nil {
				baseCtx = context.Background()
			}
			sigCtx, stopSignals := signal.NotifyContext(baseCtx, os.Interrupt, syscall.SIGTERM)
			defer stopSignals()

			for {
				w := &watcher{
					root:      executionID,
					tree:      tree && executionID != "",
					eventType: eventType,
					untilDone: untilDone,
					noLogs:    noLogs,
					json:      commands.GetOutputFormat() == "json",
				}
				if quiet {
					w.problems = newProblemFilter()
				}
				if err := w.run(sigCtx, c); err != nil || !followRestarts || sigCtx.Err() != nil {
					return err
				}
				next := successorOf(sigCtx, c, executionID)
				if next == "" {
					return nil
				}
				fmt.Printf("RESTARTED %s %s\n", executionID, next)
				executionID = next
			}
		},
	}

	cmd.Flags().StringVar(&executionID, "execution", "", "Only events of this execution")
	cmd.Flags().StringVar(&eventType, "type", "",
		"Only events of this kind: NodeStatusChanged, WorkflowStatusChanged, ControlOutcome, LogLine,\n"+
			"InputRequested, InputEscalated, InputAnswered, InputExpired or InputCancelled")
	cmd.Flags().BoolVar(&untilDone, "until-done", false, "Exit when the execution has ended (needs --execution)")
	cmd.Flags().BoolVar(&tree, "tree", true,
		"With --execution, also watch the runs it starts as sub-workflows, at any depth")
	cmd.Flags().BoolVar(&followRestarts, "follow-restarts", false,
		"With --until-done, go on with the run that restarts the execution when it ends")
	cmd.Flags().BoolVar(&noLogs, "no-logs", false, "Leave out log lines")
	cmd.Flags().BoolVar(&quiet, "quiet", false,
		"Only problems (with their reasons and error lines) and the final workflow status")
	return cmd
}

// watcher prints events and decides when --until-done is satisfied. Events arrive from the stream
// and from the status poll, so handle is serialized.
type watcher struct {
	mu        sync.Mutex
	finished  bool
	cancel    context.CancelFunc
	root      string            // the watched execution; --until-done ends with it
	tree      bool              // the runs root starts are streamed too
	workflows map[string]string // workflow of each child run seen, to name its lines
	eventType string
	untilDone bool
	noLogs    bool
	json      bool
	problems  *problemFilter     // set by --quiet
	seen      map[nodeKey]string // last status printed per execution node, to drop snapshot/stream duplicates
	pollErr   error              // the status poll's permanent failure (404, 401, ...), which ends the watch
	pollWarn  bool               // the first transient poll failure has been reported
}

// run streams the events until the watch ends: ctx ends, the stream fails, or with --until-done the
// root execution ends.
func (w *watcher) run(parent context.Context, c *client.Client) error {
	ctx, cancel := context.WithCancel(parent)
	defer cancel()
	w.cancel = cancel
	if w.untilDone {
		go w.pollStatus(ctx, c, w.root)
	}
	err := c.StreamTreeEvents(ctx, w.root, w.tree, w.eventType, "", w.handle)
	if pollErr := w.pollFailure(); pollErr != nil {
		return pollErr
	}
	if errors.Is(err, context.Canceled) {
		return nil
	}
	return err
}

// successorOf finds the run that restarted executionID (its restartOf), waiting a little for it to
// start; "" when none starts in time or the execution cannot be read.
func successorOf(ctx context.Context, c *client.Client, executionID string) string {
	progress, err := c.GetExecution(executionID)
	if err != nil {
		return ""
	}
	deadline := time.Now().Add(successorWait)
	for {
		if history, err := c.GetWorkflowHistory(progress.SessionID); err == nil {
			for _, run := range history {
				if run.RestartOf == executionID {
					return run.ExecutionID
				}
			}
		}
		if time.Now().After(deadline) {
			return ""
		}
		select {
		case <-ctx.Done():
			return ""
		case <-time.After(successorPoll):
		}
	}
}

func (w *watcher) pollFailure() error {
	w.mu.Lock()
	defer w.mu.Unlock()
	return w.pollErr
}

// pollFailed ends the watch on a permanent error and warns once about a transient one, so a wrong
// id, a revoked token or a dead server is not silent.
func (w *watcher) pollFailed(err error) {
	w.mu.Lock()
	defer w.mu.Unlock()
	if client.IsPermanent(err) {
		w.pollErr = err
		w.cancel()
		return
	}
	if !w.pollWarn {
		w.pollWarn = true
		fmt.Fprintf(os.Stderr, "warning: cannot read the execution status (will keep trying): %v\n", err)
	}
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
	child := w.root != "" && e.ExecutionID != "" && e.ExecutionID != w.root
	if child && e.WorkflowID != "" {
		if w.workflows == nil {
			w.workflows = map[string]string{}
		}
		w.workflows[e.ExecutionID] = e.WorkflowID
	}
	if !child && w.untilDone && e.Type == client.EventWorkflowStatusChanged &&
		client.IsTerminalStatus(e.Status) {
		w.finished = true
		defer w.cancel()
	}
	// a child run that went well is not news when only problems are wanted
	if child && w.problems != nil && e.Type == client.EventWorkflowStatusChanged && e.Status == "SUCCESS" {
		return nil
	}
	if w.noLogs && e.Type == client.EventLogLine {
		return nil
	}
	if _, heal := healLine(e); w.problems != nil && !heal && !isProblemOrEnd(e) && !isWarnOrError(e) {
		return nil
	}
	if w.eventType != "" && !strings.EqualFold(w.eventType, e.Type) {
		return nil
	}
	if child && !w.json {
		e = w.named(e)
	}
	w.print(e)
	return nil
}

// named marks an event of a child run with the child's workflow (or the start of its execution id
// until the workflow is known): "check/style" for a node, "check" for the run itself.
func (w *watcher) named(e client.ExecutionEvent) client.ExecutionEvent {
	name := w.workflows[e.ExecutionID]
	if name == "" {
		name = e.ExecutionID
		if len(name) > 8 {
			name = name[:8]
		}
	}
	if e.Type == client.EventWorkflowStatusChanged {
		e.Status = name + " " + e.Status
	} else if e.NodeID != "" {
		e.NodeID = name + "/" + e.NodeID
	}
	return e
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
	if w.problems != nil {
		for _, line := range w.problems.render(e) {
			fmt.Println(line)
		}
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
		if err != nil {
			w.pollFailed(err)
		} else {
			for _, t := range progress.Tasks {
				if client.IsTerminalStatus(t.Status) {
					_ = w.handle(client.ExecutionEvent{
						Type:        client.EventNodeStatusChanged,
						ExecutionID: executionID,
						NodeID:      t.NodeID,
						Status:      t.Status,
						Metadata:    t.Metadata, // a failed node's reason, if its event was missed
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
	case client.EventInputRequested:
		if e.Request == nil {
			return "INPUT requested"
		}
		return fmt.Sprintf("INPUT requested by %s (%s, %s risk)",
			e.Request.NodeID, e.Request.Spec.Kind, e.Request.Spec.Risk)
	case client.EventInputEscalated:
		if e.Request == nil {
			return "INPUT escalated"
		}
		return fmt.Sprintf("INPUT of %s escalated to %s", e.Request.NodeID, e.Request.Tier)
	case client.EventInputAnswered:
		return "INPUT answered: " + e.Decision
	case client.EventInputExpired:
		return "INPUT expired"
	case client.EventInputCancelled:
		return "INPUT cancelled: " + e.Reason
	default:
		return strings.ToUpper(e.Type)
	}
}
