# Core Module CLAUDE.md

## Overview
Core service layer and workflow orchestration engine.

## Build & Development Commands
```bash
# Run tests for core module
./gradlew :core:test

# Run a single test class
./gradlew :core:test --tests com.infenia.yukta.service.orchestrator.WorkflowOrchestratorTest
```

## Key Architecture & Patterns

### Reactive Streams (Core Pattern)
All service logic uses **Project Reactor** (`Mono`, `Flux`).
- Services use `Mono` for single operations, `Flux` for streams.
- Avoid blocking operations.

### Workflow Orchestration
- `com.infenia.yukta.service.orchestrator.WorkflowOrchestrator`: DAG orchestration logic, split across `assembly/`, `compiler/`, `preparator/`, `strategy/`, `stream/`, `tracker/`, and `validator/` subpackages.
- `tracker/` keeps the queue and projections in `DefaultTaskTrackerService`, one run's state in
  `WorkflowState` and the spans and meters in `RunTelemetry`. `DefaultControlBusGateway` delegates
  starting (admission, shared prepare, idempotency) to `WorkflowStarter`.
- `com.infenia.yukta.service.plugin.PluginRegistry`: Plugin registration.
- Executes a Directed Acyclic Graph (DAG) of nodes and edges.

### Immutability & Records
Use **Java records** for data models (preferred) or **Lombok** (`@RequiredArgsConstructor`, `@Getter`) for immutable objects.

### Validation
- Custom validators in `com.infenia.yukta.validation`.
- Use Jakarta Bean Validation.

### Logging API Design
- **PluginLoggerFactory**: Uses `@FunctionalInterface` with a single abstract method `create(LoggerContext)`.
  - `LoggerContext` is a record encapsulating execution context (executionId, sessionId, pluginId, pluginName).
  - Convenience default methods provided for 3 and 4-parameter creation to maintain backward compatibility.
  - Implementation: `DefaultPluginLoggerFactory` extracts values from LoggerContext and passes to `DefaultPluginLogger`.

### Node failure policy (`engine` config key)
Any node's config may carry a reserved `engine` object; plugins never see these options.
```json
"engine": {
  "retry":   { "maxAttempts": 3, "backoffMs": 200, "maxBackoffMs": 5000 },
  "onError": "ROUTE",
  "trigger": "ALL_DONE",
  "elementTimeoutMs": 20000,
  "deadlineMs": 600000,
  "failureBudget": { "maxFailures": 10, "windowMs": 60000 }
}
```
- `retry` (processors only): re-run the plugin for the failing message with exponential backoff and
  jitter; the plugin is then called once per message, so use it with per-message processors.
  Every attempt counts as node activity, `IllegalArgumentException` (bad input) is not retried, and
  `maxBackoffMs` must be below the node's timeout (checked by the validator).
- `onError` (processors only): `FAIL` (default), `CONTINUE` (drop the message), or `ROUTE` (send it,
  tagged with its failure, to the node's `error` output port; wire an edge with `sourcePort: "error"`
  to a dead-letter node; an edge without a `sourcePort` never carries a dead letter). A routed message carries the headers `failure.attempts`,
  `failure.exception` (class name) and `failure.time` (ISO-8601) next to `origDest`/`failureReason`.
- `retry.retryOn` / `retry.noRetryOn`: lists of exception class names (simple or fully qualified;
  subclasses and causes match). `noRetryOn` wins; with `retryOn` only those are retried; otherwise
  everything except `IllegalArgumentException`. The two must not share a class.
- `failureBudget {maxFailures, windowMs}` (needs `onError` CONTINUE or ROUTE): when more than
  `maxFailures` messages fail within the window, the node fails instead of dropping or routing the
  next one. Counted per run (`FailureLedger`).
- `trigger` (nodes with several parents): `ALL_SUCCESS` (default, fail with a failing parent),
  `ALL_DONE` (continue on the other parents) or `ONE_SUCCESS` (continue, but fail if every parent
  failed).
- `elementTimeoutMs` (processors): longest time the plugin may stay silent for one message; it fails
  that attempt, so `retry` and `onError` apply. It is only useful below the node timeout.
- `deadlineMs` (any node): longest the node may run, from its first activity. Not restarted by
  output or heartbeats; paused time does not count; must be above `elementTimeoutMs`. The node ends
  `TIMED_OUT`.
- With `retry` or `onError` CONTINUE/ROUTE a message produces all of its output or none: what an
  attempt emitted before it failed is held back and dropped, so a retry or a dead letter never
  comes on top of a partial result. The output of one message is therefore buffered until its
  attempt ends.
- A processor skipped from the start (queued run, supervisor) can be unskipped later; a terminal's
  idle timeout and `deadlineMs` also cover what it does after its last input. Applying a
  configuration drops the cached prepared workflow if the stored definition has moved on.
- With `retry`/`onError`/`elementTimeoutMs` the plugin is called once per message and the message
  carries an `attempt` header (1, 2, ...) next to its `id`, so a plugin can de-duplicate side effects.
Malformed policies are rejected by `WorkflowValidator` with the node id and option. Parsing lives in
`com.infenia.yukta.model.workflow.NodePolicy`; behaviour in `service.orchestrator.stream.NodePolicies`.

### Restart and execution log
- A run that falls out of the archive while it is still running keeps its recorded outputs until it
  ends (they are cleared then).
- Recent executions stay restartable after they end (`ExecutionArchive`: graph, payload, recorded
  node outputs). `yukta.restart.retained-executions` (default 10) and
  `yukta.restart.messages-per-node` (default 200) and `yukta.restart.bytes-per-node` (default 4 MiB;
  strings and byte arrays by size, other payloads count 1 KiB) bound the memory; a restart that needs a node's
  output that exceeded the cap is refused rather than replayed partially.
- Restart-from-node re-runs the node and its descendants and replays the recorded output of the
  nodes that feed them (`RestartPlan`); independent branches are left alone.
- Set `yukta.restart.dir` to keep node outputs on disk (`FileNodeCheckpointStore`, one JSON line per
  message, no caps; payloads come back as plain JSON values). With it and `yukta.execution-log.dir`,
  `ExecutionResumer.resume(executionId)` continues a run that a restart aborted: the log holds the
  payload (`RUN_STARTED`); nodes that finished (a durable end marker, below) replay their
  recorded output, the rest run again. `NODE_FINISHED` log events are history only. A run resumes once (`RUN_RESUMED`) and only if its definition
  is unchanged. REST: `POST /api/executions/{id}/recover` (409 `ConflictException` if the run was not
  aborted, was already resumed or its workflow changed); MCP: `control_workflow` action `RECOVER`.
  Only one resume per run is accepted (the claim is kept in memory and dropped on failure). The end
  of a node is a durable marker kept with its output (`NodeCheckpointStore.markComplete`: the file
  store forces the output, writes and forces `<node>.done` and the directory before the completion
  goes on downstream; `yukta.checkpoint.errors` counts markers that failed, and that node then runs
  again). A resume skips only nodes with such a marker. What remains is the instant between a
  plugin's side effect and the marker, so make plugins idempotent on the message `id`.
- Restart-from-node on a run that is still going replays only parents that have finished (the
  durable end marker); a replayed output is marked finished under the new run, so a second resume
  does not run those nodes again. A resume claim is dropped if the caller cancels.
- Run starts and ends go to an `ExecutionLogStore`. By default it is in memory; set
  `yukta.execution-log.dir` to keep `executions.jsonl` on disk. On startup `ExecutionRecovery`
  marks runs that never finished as `ABORTED`, restores the newest
  `yukta.execution-log.retained-runs` (default 500) into history, and rewrites the log without
  older ones. File access runs on one background writer thread, and the file is compacted to the
  newest `retained-runs` runs after every `yukta.execution-log.compact-after` (default 10000) events.
  Compaction and recovery keep the newest runs by their last event, and compaction never drops a
  run without an end. Events are not `fsync`ed, so a power loss can lose the last few; a rewritten
  file (compaction, session config) is forced to disk before it replaces the old one.

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
  reload.
- Waiting starts are admitted by priority: a start payload key `yukta.priority` (number, higher
  first, default 0), then arrival order. `ExecutionScheduler.setLimits` changes the limits at run time; through the gateway
  (`changeLimits`) the change is recorded in the log under run id `scheduler` (the newest
  `retained-runs` of them are kept). Limits are not restored from the log on startup.
- Concurrent starts of a workflow that is not prepared yet share one preparation (per definition
  content). A prepared workflow is cached only if its definition is still the stored one.
- A start asks the scheduler for its slot after the workflow is prepared and before the execution id
  is handed out (`WorkflowOrchestrator.execute(..., admission)`), so two starts racing for the last
  slot cannot both get an id: the loser gets HTTP 429 and no run is created.
- Restarting a finished run goes through the same admission check as a start (HTTP 429 when full).

### Control audit and stop mode
Every control command sent to a known run is recorded in the execution log as `CONTROL_APPLIED`
(status = command name) or `CONTROL_REJECTED` (status = `command: reason`). Commands for unknown
executions are not recorded. `POST /api/workflow/{session}/{workflow}/stop?mode=DRAIN|CANCEL|KILL`
selects how a run stops (default `DRAIN`). Restart-from-node after a server restart is not supported (the archive lives in
memory); use `ExecutionResumer` for aborted runs.
`yukta.executions.running` and `yukta.executions.queued` gauges report the scheduler.
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
`ACCEPTED`; a node that is not waiting for it gives HTTP 409. `WAIT_FOR_SIGNAL` (internal-core) holds
each message until its signal arrives (`signal`, `timeoutSeconds` default 3600, `onTimeout`,
`onReject`) and tags it with `signal.name/decision/data`; the engine timeout for the node is the wait
plus 30 s. The wait is in memory: a resumed run waits again.

### Workflow definitions and their versions
With `yukta.session.store-type=file`, `FileWorkflowDefinitionStore` keeps definitions under
`<baseDir>/workflows/<session>/<workflow>/` (`current.json`, `history/<version>.json`; ids are
URL-encoded, writes are atomic and fsynced) so they survive a restart; `in-memory` keeps them in
memory. Both keep the newest 20 saved versions of a workflow (identical saves add none).
`WorkflowDefinitionStore.revisions/findRevision` and the web rollback endpoint use them.

### Sub-workflows
`SUB_WORKFLOW` (internal-core) runs another stored workflow of the same session per message through
`SubWorkflowGateway` (`SubWorkflowRunner`): a normal run with its own execution id, tracked and
limited like any other, whose terminal nodes feed a `ResultCollector` in the Reactor context. The
node emits `{executionId, results[]}`; a failing child fails the node. The child start payload
carries `yukta.depth` and `yukta.parentExecutionId`; nesting is limited to 5. With
`yukta.execution.max-concurrent` the limit must allow parent and children together.

### LLM budgets
`LLM_CALL` takes `budget: {maxTokens, maxCost}` (tokens are input plus output; `maxCost` needs
`prices`). Use is counted per run across all `LLM_CALL` nodes (the plugin keeps the newest 10000
runs); once a limit is reached the next call fails like a failed call (`failureMode` and
`routeByStatus` apply, so a workflow can fall back). One call may overshoot the limit. Not yet:
limits per agent or per provider, or a rate limit per provider (see the backlog).

### Supervisors (rule engine / AI / custom code on the control bus)
A supervisor implements `ExecutionSupervisor` (plugin-api) and is registered as a Spring bean:
- `supervise(Flux<ExecutionEvent>)` receives the global event feed (`ExecutionEventFeed`, fed by the
  tracker and by command outcomes; best effort, never blocks a run) and returns the
  `ExecutionControlCommand`s to apply. `SupervisorHost` subscribes each supervisor once, applies its
  commands through `DirectiveDispatcher` with the supervisor id as actor (Reactor context key
  `ControlActor.CONTEXT_KEY`), and isolates it: failures resubscribe with backoff, commands are
  limited to `yukta.supervisor.max-commands-per-second` (default 20), `yukta.supervisor.dry-run=true`
  only records them, and a supervisor never sees the outcome of its own commands.
- `review(ControlRequest)` can refuse any command, human or automated, before it runs
  (`ControlReviewer`; first refusal wins in `order()`; an erroring reviewer refuses). The caller gets
  `ControlRejectedException` (HTTP 403) and the log gets `CONTROL_REJECTED`.
- Commands sent by a supervisor are logged as `PauseWorkflowCommand [by <id>]`.
- The feed delivers on the publisher's thread, so each supervisor gets its events through a
  bounded buffer (256) on `boundedElastic`; a slow or blocking supervisor drops events (counted in
  `yukta.tracker.dropped{kind=supervisor}`) and never holds the tracker up.
- The feed is exposed at `GET /api/events/stream` (SSE; filters `executionId`, `type`). External
  agents can supervise through it and the REST/MCP control endpoints without JVM code.
- `SupervisorHost.status()` (REST `GET /api/supervisors`, shown on the UI Control page) reports each supervisor's kind (`CONTROL_PLANE` or `SUPERVISOR`), order, dry-run flag and command counts from `yukta.supervisor.commands`.
- Reference implementation: `plugins/supervisors/rule-supervisor` (JSON rules + SpEL, enabled with
  `yukta.supervisor.rules.file`). Metrics: `yukta.supervisor.commands{id,outcome}`.

### Node status only moves forward
`WorkflowState.updateTask` ignores a status that would reopen an ended node or step a node back
(`PENDING` < `QUEUED` < `RUNNING` < any terminal status); the metadata of such an event is still
merged. `WorkflowStateTransitionTest` checks this on thousands of random event orders, and
`StreamTopologyDecoratorBackpressureTest` (a 20-node chain, 10k messages, bounded memory) guards the
streaming side as part of `check`.

Node spans are opened only for a status the run state accepted, so a late `RUNNING` for an ended
node leaves no open span. `resume` reads the log and checkpoint files on `boundedElastic`.

### Tracker queue
State and workflow events are never dropped: they go to an unbounded queue. Log lines are best
effort: more than 10000 waiting are dropped and counted in `yukta.tracker.dropped{type=log}`
(`type=state` counts events lost because the tracker itself had ended). Use `SinkEmit.offer`, not
`Sinks.Many#emitNext`, for any bounded sink: `emitNext` terminates the sink on overflow.

### Node timeouts
A node's idle timeout is `timeoutSeconds` from its config, else the plugin's
`getDefaultTimeout(config)`. `PROCESS_EXECUTOR` uses its `timeout` (default 300 s) plus 10 % (at least
5 s), `LLM_CALL` covers every attempt (`timeout` and `retries`) plus the backoff, `HTTP_REQUEST` uses
its `timeout` plus 5 s; other plugins keep 30 s. A timeout given as an expression is not known when the
workflow is compiled, so the node starts with the default and is re-timed when its first message
arrives: the node config is resolved against that message (`NodeTimeouts`) and `timeoutSeconds`, else
the plugin's `getDefaultTimeout(config)`, is applied. Write a plugin's own number option as a typed
expression (`${payload.secs:int}`); an untyped one resolves to text. Triggers have no input, so they
keep the default.

A long-running plugin can keep its node alive by calling the `Runnable` found under
`WorkflowContext.HEARTBEAT_KEY` in the Reactor context (`Mono.deferContextual`); every call restarts
the idle clock. `engine.deadlineMs` adds a node-level wall-clock limit on top of the idle timeout, `elementTimeoutMs`
and the run deadline.

Every progress snapshot (REST, SSE, MCP) carries `definitionVersion`, the content hash of the graph
the run uses (the same value the archive and the execution log store). The control-bus consumer
resubscribes with capped backoff if its stream ever fails, until `shutdown()`.

### Breakpoints and tracing
`ReactiveControlValve.setBreakpoint(predicate)` (per node, via `ExecutionControl.nodePauseValves()`;
remote callers use `ControlBusGateway.addBreakpoint`) holds the node on the first element that matches until it is resumed. Each node run is one
Micrometer observation `yukta.node` (tags `module`, `status`), which becomes a span when a tracer
is on the classpath, as a child of the run's `yukta.run` observation. Re-running a node is
`RestartFromNode`.

### Reading the environment from a workflow
`${env.X}` and `${sys.x}` read only an allowlist: `YUKTA_VAR_*` environment variables, `yukta.var.*`
system properties, a short list of harmless ones (`HOME`, `USER`, `PATH`, `user.name`, `os.name`,
...), and names listed in `yukta.variables.allowed` (system property) or `YUKTA_VARIABLES_ALLOWED`
(env), comma separated. Names that look like secrets stay blocked even if listed. Use `decrypted:`
for secrets. `GraalJsScriptEngine` lets scripts `Java.type` only `java.time.*` and a few value
classes and denies reflection on `Class`. `yukta.process.allowed-commands` (comma separated,
default empty = any) limits `PROCESS_EXECUTOR` to those executables and refuses `useShell`.

### Validation limits and metrics
- `WorkflowValidator` rejects duplicate node ids and edges, an edge `sourcePort` the source plugin
  does not declare (`error` is allowed on nodes with `onError: ROUTE`), and graphs over 500 nodes or
  2000 edges.
- The tracker records Micrometer `yukta.node.executions` (tags `module`, `status`) and
  `yukta.node.duration` (tag `module`) when a node reaches a terminal status, `yukta.node.retries`
  (tag `module`) per retried message, `yukta.execution.duration` (tag `status`) per run and
  `yukta.execution.queue.wait` per start that waited. `yukta.checkpoint.bytes` is the node output the
  checkpoint store holds. The full list is in the guide ("Metrics and tracing").
- Tracing: every run is one `yukta.run` observation, the parent of its `yukta.node` observations.
  Engine log lines carry `executionId`/`nodeId` as structured key-values; there is no MDC.

## Key File Locations
- **Services**: `src/main/java/com/infenia/yukta/service/`
- **Validation**: `src/main/java/com/infenia/yukta/validation/`
- **Module guide (interactive)**: `docs/core-module-guide.html`

## Imports
@.claude/rules/coding-standards.md
@.claude/rules/git-workflow.md

## Testing speed
Follow the tiered "Test Execution Policy (speed)" in the root `CLAUDE.md`: run `--tests` for the classes you changed first; full `:core:test` only when many files changed.
