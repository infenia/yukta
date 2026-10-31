---
paths:
  - "src/**/service/orchestrator/**"
  - "src/**/model/workflow/**"
  - "src/**/service/control/valve/**"
---

# Engine: node policy, status, timeouts, breakpoints

Reference for the orchestrator and node streams. Moved from `core/CLAUDE.md`; loads when you touch `service/orchestrator`, `model/workflow` or the control valves.

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
- `stallAfterMs` (processors, triggers): a node that shows no activity (input, output, heartbeat or
  live log line) for this long logs one WARN line starting `NodeStalled:` (`NodeStreams.STALLED_PREFIX`);
  rules and `watch --quiet` see it as a WARN log line. It only reports. Paused time does not count.
- `concurrencyGroup` (processors): the node counts against `yukta.concurrency.groups.<name>=<n>`
  (`ConcurrencyGroups`); a node over the limit waits `QUEUED` before its first input is let through
  and holds its place until its output ends. A group with no configured limit is not limited.
- `placement {pool}` (processors, see "Workers"): run the node on a worker of the pool (default `default`).
- `retry` (processors only): re-run the plugin for the failing message with exponential backoff and
  jitter; the plugin is then called once per message, so use it with per-message processors.
  Every attempt counts as node activity, `IllegalArgumentException` (bad input) is not retried, and
  `maxBackoffMs` must be below the node's timeout (checked by the validator).
- `onError` (processors only): `FAIL` (default), `CONTINUE` (drop the message), or `ROUTE` (send it,
  tagged with its failure, to the node's `error` output port; wire an edge with `sourcePort: "error"`
  to a dead-letter node; an edge without a `sourcePort` never carries a dead letter). A routed message carries the headers `failure.attempts`,
  `failure.exception` (class name), `failure.message` (error text) and `failure.time` (ISO-8601) next to `origDest`/`failureReason`.
- `retry.retryOn` / `retry.noRetryOn`: lists of exception class names (simple or fully qualified;
  subclasses and causes match). `noRetryOn` wins; with `retryOn` only those are retried; otherwise
  everything except `IllegalArgumentException`. The two must not share a class.
- `failureBudget {maxFailures, windowMs}` (needs `onError` CONTINUE or ROUTE): when more than
  `maxFailures` messages fail within the window, the node fails instead of dropping or routing the
  next one. Counted per run (`FailureLedger`).
- `edgeBuffer {size, overflow}` (any node with a processor; `DROP_OLDEST` default or `DROP_NEWEST`): a bounded buffer in front of the node. When the node is slower than its parents it drops messages (counted in `yukta.edge.dropped{type=<node type>}`) instead of pacing the fan-out. Opt-in; the default stays lossless.
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

### Node status only moves forward
A node reports `RUNNING` (and gets its `startTime`) at its first activity (`NodeActivity.onFirstTouch`: first input, or subscribe for a trigger), not when the graph is wired; one that ends without any still reports `RUNNING` first. Per-node times in the progress API are therefore its own.

`WorkflowState.updateTask` ignores a status that would reopen an ended node or step a node back
(`PENDING` < `QUEUED` < `RUNNING` < any terminal status); the metadata of such an event is still
merged. `WorkflowStateTransitionTest` checks this on thousands of random event orders, and
`StreamTopologyDecoratorBackpressureTest` (a 20-node chain, 10k messages, bounded memory) guards the
streaming side as part of `check`.

Node spans are opened only for a status the run state accepted, so a late `RUNNING` for an ended
node leaves no open span. `resume` reads the log and checkpoint files on `boundedElastic`.

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

A node that ends `FAILURE` or `TIMED_OUT` reports its reason in the metadata of its status event
(`NodeStreams.ERROR_KEY`, `error`: the exception message, cut to 4000 characters at each end); an
`UPSTREAM_FAILED` node carries none. Watchers (SSE, `yukta-cli controlbus watch --quiet`) read the cause
from there instead of the logs.

### Breakpoints and tracing
`ReactiveControlValve.setBreakpoint(predicate)` (per node, via `ExecutionControl.nodePauseValves()`;
remote callers use `ControlBusGateway.addBreakpoint`) holds the node on the first element that matches until it is resumed. Each node run is one
Micrometer observation `yukta.node` (tags `module`, `status`), which becomes a span when a tracer
is on the classpath, as a child of the run's `yukta.run` observation. Re-running a node is
`RestartFromNode`.
