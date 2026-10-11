# 05 Governance (4→5)

Gaps: limits global/per-workflow only; no per-session quota; setLimits not persisted/audited.
- `yukta.execution.max-concurrent-per-session` and queue capacity per workflow in `ExecutionScheduler`; fairness (weighted round-robin across sessions within same priority) to prevent starvation.
- Audit `setLimits` changes in the log; metrics `yukta.executions.rejected{reason}`.
- Idempotency store behind small interface so it survives restart when `yukta.execution-log.dir` is set.
- Tests: fairness ordering, per-session cap, rejection reasons.

## Done when
`./gradlew spotlessApply :core:check` is green, the guide row for this area reads 5 with no "partial"/"not built" cells, and core/CLAUDE.md is updated.
