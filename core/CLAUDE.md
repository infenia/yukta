# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited

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
  "deadlineMs": 600000
}
```
- `retry` (processors only): re-run the plugin for the failing message with exponential backoff and
  jitter; the plugin is then called once per message, so use it with per-message processors.
  Every attempt counts as node activity, `IllegalArgumentException` (bad input) is not retried, and
  `maxBackoffMs` must be below the node's timeout (checked by the validator).
- `onError` (processors only): `FAIL` (default), `CONTINUE` (drop the message), or `ROUTE` (send it,
  tagged with its failure, to the node's `error` output port; wire an edge with `sourcePort: "error"`
  to a dead-letter node).
- `trigger` (nodes with several parents): `ALL_SUCCESS` (default, fail with a failing parent),
  `ALL_DONE` (continue on the other parents) or `ONE_SUCCESS` (continue, but fail if every parent
  failed).
- `elementTimeoutMs` (processors): longest time the plugin may stay silent for one message; it fails
  that attempt, so `retry` and `onError` apply. It is only useful below the node timeout.
- `deadlineMs` (any node): longest the node may run, from its first activity. Not restarted by
  output or heartbeats; paused time does not count; must be above `elementTimeoutMs`. The node ends
  `TIMED_OUT`.
- With `retry`/`onError`/`elementTimeoutMs` the plugin is called once per message and the message
  carries an `attempt` header (1, 2, ...) next to its `id`, so a plugin can de-duplicate side effects.
Malformed policies are rejected by `WorkflowValidator` with the node id and option. Parsing lives in
`com.infenia.yukta.model.workflow.NodePolicy`; behaviour in `service.orchestrator.stream.NodePolicies`.

### Restart and execution log
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
  payload (`RUN_STARTED`) and each successful node (`NODE_FINISHED`); finished nodes with complete
  output replay, the rest run again. A run resumes once (`RUN_RESUMED`) and only if its definition
  is unchanged. There is no REST/MCP endpoint for it yet.
- Run starts and ends go to an `ExecutionLogStore`. By default it is in memory; set
  `yukta.execution-log.dir` to keep `executions.jsonl` on disk. On startup `ExecutionRecovery`
  marks runs that never finished as `ABORTED`, restores the newest
  `yukta.execution-log.retained-runs` (default 500) into history, and rewrites the log without
  older ones. File access runs on one background writer thread, and the file is compacted to the
  newest `retained-runs` runs after every `yukta.execution-log.compact-after` (default 10000) events.
  Events are not `fsync`ed, so a power loss can lose the last few.

### Execution limits and idempotent starts
- `ExecutionScheduler` admits executions. By default there is no limit. Set
  `yukta.execution.max-concurrent` and/or `yukta.execution.max-concurrent-per-workflow` to limit;
  a start that does not fit waits (`yukta.execution.on-limit=QUEUE`, at most
  `yukta.execution.queue-capacity`, default 100, shown as status `QUEUED`; a start that waits longer
  than `yukta.execution.queue-timeout`, default off, ends `TIMED_OUT`) or is refused with HTTP 429
  (`REJECT`, or queue full). On shutdown nothing new is admitted, queued starts are refused, and
  running ones get `yukta.execution.shutdown-timeout` (default 30s) to finish.
- `POST /workflow/start` accepts an optional `Idempotency-Key` header: a repeat with the same key for
  the same session and workflow within `yukta.start.idempotency-ttl` (default 10m) returns the first
  execution id instead of starting another. A failed start is not remembered.
- Waiting starts are admitted by priority: a start payload key `yukta.priority` (number, higher
  first, default 0), then arrival order. `ExecutionScheduler.setLimits` changes the limits at run time.
- Concurrent starts of a workflow that is not prepared yet share one preparation (per definition
  content). A prepared workflow is cached only if its definition is still the stored one.
- Restarting a finished run goes through the same admission check as a start (HTTP 429 when full).

### Control audit and stop mode
Every control command sent to a known run is recorded in the execution log as `CONTROL_APPLIED`
(status = command name) or `CONTROL_REJECTED` (status = `command: reason`). Commands for unknown
executions are not recorded. `POST /api/workflow/{session}/{workflow}/stop?mode=DRAIN|CANCEL|KILL`
selects how a run stops (default `DRAIN`). Restart-from-node after a server restart is not supported (the archive lives in
memory); use `ExecutionResumer` for aborted runs.
`yukta.executions.running` and `yukta.executions.queued` gauges report the scheduler.

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
`ReactiveControlValve.setBreakpoint(predicate)` (per node, via `ExecutionControl.nodePauseValves()`)
holds the node on the first element that matches until it is resumed. Each node run is one
Micrometer observation `yukta.node` (tags `module`, `status`), which becomes a span when a tracer
is on the classpath. Re-running a node is `RestartFromNode`.

### Validation limits and metrics
- `WorkflowValidator` rejects duplicate node ids and edges, an edge `sourcePort` the source plugin
  does not declare (`error` is allowed on nodes with `onError: ROUTE`), and graphs over 500 nodes or
  2000 edges.
- The tracker records Micrometer `yukta.node.executions` (tags `module`, `status`) and
  `yukta.node.duration` (tag `module`) when a node reaches a terminal status.

## Key File Locations
- **Services**: `src/main/java/com/infenia/yukta/service/`
- **Validation**: `src/main/java/com/infenia/yukta/validation/`
- **Module guide (interactive)**: `docs/core-module-guide.html`

## Imports
@.claude/rules/coding-standards.md
@.claude/rules/git-workflow.md
