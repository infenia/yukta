---
paths:
  - "src/**/service/orchestrator/tracker/**"
  - "src/**/service/orchestrator/validator/**"
  - "src/**/service/result/**"
  - "src/**/service/streaming/**"
---

# Tracker, execution results, validation limits and metrics

Reference for the tracker queue, `ExecutionResultService`, validator limits and meters. Moved from `core/CLAUDE.md`.

### Execution results
`ExecutionResultService` (`service/result`) records what reaches every terminal node of every run
(`TerminalNodeAssemblerStrategy` publishes each input message, a `NODE_END` per terminal;
`WorkflowOrchestrator` publishes `EXECUTION_END` and completes the stream). It is a separate
channel from logs: payloads are kept whole (not cut to 2,048 characters) and are typed. The live
stream is complete; only the replay buffer is bounded (`yukta.results.retained-events`, default 1000
per run; `yukta.results.retention` 1h after last use; `yukta.results.max-executions` 200) and the
end events carry `truncated` when it dropped anything. Clients: REST `GET
/api/sessions/{s}/executions/{e}/results` (SSE, or JSON with `Accept: application/json` and
`afterCursor`), MCP `get_execution_result`, CLI `yukta-cli result`, and the RESULT block of the UI
progress panel. Results are in memory only; they are gone after a restart.

### Tracker queue
A plugin can write log lines while it runs through the `PluginLogSink` in the Reactor context (see plugin-api). Those lines reach the log feed, the stores and `GET .../logs` (SSE) as they are written, with their stdout/stderr label (`emitLogEvent(..., stderr)`). A node that did so (`NodeActivity.hasLoggedLive`) is not echoed again from its output message; other nodes still log that message cut to 2,048 characters. `PROCESS_EXECUTOR` streams every line, so its `maxOutputLines`/`maxOutputBytes` limit only the output kept in its result message. MCP `get_execution_logs` takes `afterCursor` (the previous call's `nextCursor`) to read only new lines.
State and workflow events are never dropped: they go to an unbounded queue. Log lines are best
effort: more than 10000 waiting are dropped and counted in `yukta.tracker.dropped{type=log}`
(`type=state` counts events lost because the tracker itself had ended). Use `SinkEmit.offer`, not
`Sinks.Many#emitNext`, for any bounded sink: `emitNext` terminates the sink on overflow.
An execution-log write that fails (the end of a node or of a run) is logged and counted in
`yukta.tracker.log.append.failed{type=node|run}`; it never blocks the status update or the run's end.
`WorkflowOrchestrator.execute`/`rerun` register the run's control and archive it only when the
returned `Mono` is subscribed.

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
