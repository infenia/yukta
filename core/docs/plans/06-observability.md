# 06 Observability (2.5→5, but guide says 3.5 — tracing already exists)

Gaps: stale "No tracing" cell; metrics lack run-level and queue timings; no correlation in logs.
- Verify `yukta.node` observation produces spans (test with `ObservationRegistry` + `TestObservationRegistry`); fix the stale guide text.
- Add run-level observation `yukta.run` parent of node observations; timers `yukta.execution.queue.wait`, `yukta.execution.duration{status}`, retry counter `yukta.node.retries`, `yukta.checkpoint.bytes`.
- MDC (`executionId`, `nodeId`) set in plugin logger context so every log line correlates; trace id in progress snapshot.
- Guide: metrics reference table (new section).

## Done when
`./gradlew spotlessApply :core:check` is green, the guide row for this area reads 5 with no "partial"/"not built" cells, and core/CLAUDE.md is updated.
