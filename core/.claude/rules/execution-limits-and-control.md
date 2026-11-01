---
paths:
  - "src/**/service/control/**"
  - "src/**/model/control/**"
---

# Execution limits, idempotent starts, control audit, signals

Reference for the scheduler, `Idempotency-Key`, control commands, stop modes and signals. Moved from `core/CLAUDE.md`.

### Execution limits and idempotent starts
- `ExecutionScheduler` admits executions. By default there is no limit. Set
  `yukta.execution.max-concurrent` and/or `yukta.execution.max-concurrent-per-workflow` to limit;
  a start that does not fit waits (`yukta.execution.on-limit=QUEUE`, at most
  `yukta.execution.queue-capacity`, default 100, shown as status `QUEUED`; a start that waits longer
  than `yukta.execution.queue-timeout`, default off, ends `TIMED_OUT`) or is refused with HTTP 429
  (`REJECT`, or queue full). On shutdown nothing new is admitted, queued starts are refused, and
  running ones get `yukta.execution.shutdown-timeout` (default 30s) to finish.
- `yukta.execution.max-concurrent-per-session` (default none) caps one session. Within a priority a
  freed slot goes to the session with the fewest running executions (oldest first on a tie), so a
  busy session cannot starve the others. `yukta.execution.queue-capacity-per-workflow` (default none)
  caps how many starts of one workflow may wait. Refusals are counted in
  `yukta.executions.rejected{reason=shutdown|limit|queue_full|workflow_queue_full|queue_timeout}`.
- `POST /workflow/start` accepts an optional `Idempotency-Key` header: a repeat with the same key for
  the same session and workflow within `yukta.start.idempotency-ttl` (default 10m) returns the first
  execution id instead of starting another. A failed start is not remembered. With the file log
  (`yukta.execution-log.dir`) the keys are written to it (`IDEMPOTENCY_KEY`) and reloaded on startup,
  so a client retrying after a restart still gets the first execution; the window restarts at the
  reload. `yukta.start.idempotency-max-keys` (default 100000) caps the cache; a key evicted before its TTL lets a repeat start again.
- Waiting starts are admitted by priority: a start payload key `yukta.priority` (number, higher
  first, default 0), then arrival order. `ExecutionScheduler.setLimits` changes the limits at run time; through the gateway
  (`changeLimits`) the change is recorded in the log under run id `scheduler` (the newest
  `retained-runs` of them are kept). On startup `ExecutionRecovery` re-applies the newest typed
  `LIMITS_CHANGED` event (`maxConcurrent`, `maxPerWorkflow` in its `nodeStatuses`; the audit text is not parsed, and logs
  from before it restore nothing), so the log wins over the configuration from then on.
- Concurrent starts of a workflow that is not prepared yet share one preparation (per definition
  content). A prepared workflow is cached only if its definition is still the stored one.
- A start asks the scheduler for its slot after the workflow is prepared and before the execution id
  is handed out (`WorkflowOrchestrator.execute(..., admission)`), so two starts racing for the last
  slot cannot both get an id: the loser gets HTTP 429 and no run is created.
- Restarting a finished run, restarting it from a node and resuming an aborted run take their slot
  before the new id is reported (`scheduler.acquire`, passed to `execute`/`rerun` as the admission),
  so two of them cannot both use the last slot: the loser gets HTTP 429. Restarting a run that is
  still going keeps the old behaviour, because the old run holds its slot until it ends.

### Control audit and stop mode
Every control command sent to a known run is recorded in the execution log as `CONTROL_APPLIED`
(status = command name) or `CONTROL_REJECTED` (status = `command: reason`). Commands for unknown
executions are not recorded. `POST /api/workflow/{session}/{workflow}/stop?mode=DRAIN|CANCEL|KILL`
selects how a run stops (default `DRAIN`). Restart-from-node after a server restart is not supported (the archive lives in
memory); use `ExecutionResumer` for aborted runs. A restart-from-node keeps the original start
payload in its archive and log entry.
`yukta.executions.running` and `yukta.executions.queued` gauges report the scheduler.
`POST /api/admin/drain` (admin keys; `DELETE` undoes, `GET` reports `{draining, running, queued, idle}`)
makes the scheduler refuse new starts (HTTP 429, `reason=draining`) while admitted and queued runs
and children of running parents carry on, so a server can be swapped once `idle` is true. It is an
operations switch: not reviewed by `ControlReviewer` and not in the execution log.
The first terminal that fails ends the run at once and cancels the other branches (they end
`CANCELLED`); a caller that cancels a run ends it `CANCELLED` too, and a second end of a run is ignored.
`yukta.tracker.queue.depth` gauges the state queue. Progress history keeps `yukta.streaming.status-history-max-per-run` (200) snapshots per run and `...-max-executions` (1000) runs, always the newest.
`yukta.tracker.max-sessions` (default 0, off) evicts the oldest session without a running run when
more are kept in memory.
A run that outlives the session execution timeout ends `TIMED_OUT` (`ExecutionTimeoutException`); a
node that times out keeps its own status. A failure to write the end of a run to the execution log
no longer keeps the run from ending.

A restart that fails (unknown run, limits full, output incomplete, one already in progress) fails
its command, so it is audited as `CONTROL_REJECTED` and not as applied.

Gateway commands for a live run (`ControlBusGateway`). REST: `POST /api/executions/{id}/nodes/{node}/skip|retry|breakpoint` (`DELETE` clears), `/executions/{id}/priority`, `/scheduler/limits`; MCP: `control_node` `SKIP|UNSKIP|RETRY|BREAKPOINT_CLEAR` and `set_node_breakpoint`:
- `skipNode(execution, node, skip)`: a processor node passes elements through without calling the
  plugin from the next element on (the decision is taken as each element arrives; one already in the
  plugin finishes). Other node types are rejected. Unskip resumes normal processing.
- `addBreakpoint` / `clearBreakpoint`: hold a node before the first element whose `payload`,
  `payload.some.path` or `metadata.key` equals a given text (`SetBreakpointCommand`, so it is
  ordered and audited like any command).
- `retryNode(execution, node)`: re-runs a node that ended `FAILURE` or `TIMED_OUT` with the output
  its parents recorded, plus everything behind it (restart-from-node with a check).
- `changeLimits(max, perWorkflow)`: the scheduler limits; logged, not in the run log (no run).
- `changePriority(execution, priority)`: moves a start that is `QUEUED`; recorded in the run log as
  `SetPriority(n)`, rejected if the run is not waiting.

### Signals and approval steps
`SignalNodeCommand` (REST `POST /api/executions/{id}/nodes/{node}/signal?name=&decision=&data=`, MCP
`signal_node`, gateway `signalNode`) hands a named signal to the plugin of a node through the same
review, ordering and audit as other commands (`SignalNodeCommandProcessor`). The plugin must answer
`ACCEPTED` or `QUEUED`; a node of a live run that is not waiting for it yet keeps the signal for ten
minutes (at most 100, `QUEUED`) and the wait that begins takes it at once (a second signal on the same key within 5 s of an accepted one is a double submit and gets `NOT_WAITING`), while a signal for a node
that is not registered gives HTTP 409. The waits of all plugins share one `SignalWaits.shared()`, so
`GET /api/sessions/{s}/approvals`, MCP `list_pending_approvals` and the "pending approvals" strip of
the workflow page list the open ones (oldest first); answering goes through the signal endpoint.
`WAIT_FOR_SIGNAL` (internal-core) holds each message until its signal arrives (`signal`, `timeoutSeconds` default 3600, `onTimeout`,
`onReject`) and tags it with `signal.name/decision/data`; the engine timeout for the node is the wait
plus 30 s. The wait is in memory, but an answer is recorded in the run's checkpoint store as a message of the
pseudo node `signal:<node>` (payload `{messageId, name, decision, data}`, a timeout is not recorded), so a resumed run
reuses it for the same message id instead of waiting again; that needs a file or PostgreSQL store and a message that
was replayed from a finished parent.
