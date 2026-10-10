# 01 Restart and recovery (3.5→5) — biggest

Gaps: no resume after reboot; checkpoints in memory; oversize output refuses restart; no node outputs in log.
- Add `CheckpointStore` SPI next to `ExecutionLogStore` (`service/archive`, `service/log`): in-memory + file impl (`yukta.restart.dir`), reuse the single-writer-thread pattern and atomic tmp+rename from `FileSessionConfigStore`.
- `ExecutionArchive` records node outputs through the store; payloads over `bytes-per-node` spill to files (claim-check) instead of refusing restart; keep retention knobs, add `yukta.restart.spill-dir` cleanup on eviction.
- `ExecutionRecovery`: instead of only marking ABORTED, mark `RESUMABLE` when checkpoints exist; add `resume(executionId)` in the orchestrator = `RestartPlan` from the first node without a durable SUCCESS, admitted via `ExecutionScheduler`.
- Log node SUCCESS/FAILURE events (durable boundary) in `ExecutionLogStore`.
- Tests: kill-and-recover with temp dir, spill round-trip, retention eviction deletes spill, resume skips completed nodes.
- Guide: remove non-goal, matrix row 10 → ✓, "By design not built" section rewritten.

## Done when
`./gradlew spotlessApply :core:check` is green, the guide row for this area reads 5 with no "partial"/"not built" cells, and core/CLAUDE.md is updated.
