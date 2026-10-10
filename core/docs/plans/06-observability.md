<!--
SPDX-License-Identifier: Apache-2.0
SPDX-FileCopyrightText: 2026 Infenia Private Limited
-->

# 06 Observability (2.5→5, but guide says 3.5 — tracing already exists)

Gaps: stale "No tracing" cell; metrics lack run-level and queue timings; no correlation in logs.
- Verify `yukta.node` is an observation (an `ObservationRegistry` handler test, which covers the no-tracer case); a real span needs a tracer on the classpath, which core does not depend on, so a span-level assertion belongs to the application that adds one. Fix the stale guide text.
- Add run-level observation `yukta.run` parent of node observations; timers `yukta.execution.queue.wait`, `yukta.execution.duration{status}`, retry counter `yukta.node.retries`, `yukta.checkpoint.bytes`.
- MDC (`executionId`, `nodeId`) set in plugin logger context so every log line correlates; trace id in progress snapshot.
- Guide: metrics reference table (new section).

## Done when
`./gradlew spotlessApply :core:check` is green, the guide row for this area reads 5 with no "partial"/"not built" cells, and core/CLAUDE.md is updated.
