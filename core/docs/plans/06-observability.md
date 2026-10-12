# 06 Observability: what is left

Shipped: `yukta.run` observation (parent of `yukta.node`), `yukta.execution.duration`, `yukta.execution.queue.wait`, `yukta.node.retries`, `yukta.checkpoint.bytes`, `executionId`/`nodeId` as structured log key-values, the metrics table in the guide.

Open:
- Trace id in the progress snapshot (REST/SSE/MCP). Needs a decision: core has no tracer dependency, so this would be an optional field filled when a tracer is on the classpath.
- MDC is deliberately not used (structured key-values instead, see `core/CLAUDE.md`). Drop this item unless log aggregation needs MDC.

## Done when
The trace id is in the snapshot (or the item is dropped), `./gradlew spotlessApply :core:check` is green, and `core/CLAUDE.md` is updated.
