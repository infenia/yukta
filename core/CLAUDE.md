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
  "trigger": "ALL_DONE"
}
```
- `retry` (processors only): re-run the plugin for the failing message with exponential backoff and
  jitter; the plugin is then called once per message, so use it with per-message processors.
  Every attempt counts as node activity, `IllegalArgumentException` (bad input) is not retried, and
  `maxBackoffMs` must be below the node's timeout (checked by the validator).
- `onError` (processors only): `FAIL` (default), `CONTINUE` (drop the message), or `ROUTE` (send it,
  tagged with its failure, to the node's `error` output port; wire an edge with `sourcePort: "error"`
  to a dead-letter node).
- `trigger` (nodes with several parents): `ALL_SUCCESS` (default, fail with a failing parent) or
  `ALL_DONE` (continue on the other parents).
Malformed policies are rejected by `WorkflowValidator` with the node id and option. Parsing lives in
`com.infenia.yukta.model.workflow.NodePolicy`; behaviour in `service.orchestrator.stream.NodePolicies`.

### Restart and execution log
- Recent executions stay restartable after they end (`ExecutionArchive`: graph, payload, recorded
  node outputs). `yukta.restart.retained-executions` (default 10) and
  `yukta.restart.messages-per-node` (default 200) bound the memory; a restart that needs a node's
  output that exceeded the cap is refused rather than replayed partially.
- Restart-from-node re-runs the node and its descendants and replays the recorded output of the
  nodes that feed them (`RestartPlan`); independent branches are left alone.
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
  `yukta.execution.queue-capacity`, default 100, shown as status `QUEUED`) or is refused with HTTP 429
  (`REJECT`, or queue full). On shutdown nothing new is admitted, queued starts are refused, and
  running ones get `yukta.execution.shutdown-timeout` (default 30s) to finish.
- `POST /workflow/start` accepts an optional `Idempotency-Key` header: a repeat with the same key for
  the same session and workflow within `yukta.start.idempotency-ttl` (default 10m) returns the first
  execution id instead of starting another. A failed start is not remembered.
- Concurrent starts of a workflow that is not prepared yet share one preparation (per definition
  content). A prepared workflow is cached only if its definition is still the stored one.
- Restarting a finished run goes through the same admission check as a start (HTTP 429 when full).

### Tracker queue
State and workflow events are never dropped: they go to an unbounded queue. Log lines are best
effort: more than 10000 waiting are dropped and counted in `yukta.tracker.dropped{type=log}`
(`type=state` counts events lost because the tracker itself had ended). Use `SinkEmit.offer`, not
`Sinks.Many#emitNext`, for any bounded sink: `emitNext` terminates the sink on overflow.

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
workflow is compiled, so the default is used: set `timeoutSeconds` on the node in that case.

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
- **Review findings & roadmap**: `docs/core-improvements.md`

## Imports
@.claude/rules/coding-standards.md
@.claude/rules/git-workflow.md
