---
paths:
  - "src/**/service/archive/**"
  - "src/**/service/log/**"
  - "src/**/service/store/**"
  - "src/**/service/orchestrator/ExecutionResumer*"
  - "src/**/config/**"
---

# Restart, resume and the execution log

Reference for the restart archive, checkpoints, resume and the execution log (and the Plus split). Moved from `core/CLAUDE.md`.

### Restart and execution log
- A run that falls out of the archive while it is still running keeps its recorded outputs until it
  ends (they are cleared then).
- Recent executions stay restartable after they end (`ExecutionArchive`: graph, payload, recorded
  node outputs). `yukta.restart.retained-executions` (default 10) and
  `yukta.restart.messages-per-node` (default 200) and `yukta.restart.bytes-per-node` (default 4 MiB;
  strings and byte arrays by size, other payloads count 1 KiB) bound the memory. A node that exceeds
  them makes a restart that needs its output refused rather than replayed partially.
- **Plus split:** the only durable stores are the PostgreSQL ones in the AGPL `plus/persistence-postgres` module; the on-disk stores were removed. `FeatureGateEnvironmentPostProcessor` stops start-up when a setting needs a module that is not in the build (PostgreSQL, HA, workers; to gate another setting add a row to its `GATES` list), and when a removed file-store switch (`REMOVED_FILE_SETTINGS`, `yukta.session.store-type=file`) is still set; `yukta.session.base-dir` and `yukta.execution-log.fsync` did nothing alone and are ignored. `DefaultsAutoConfiguration` registers the in-memory execution log, in-memory checkpoints and `SingleServerLeader` with `@ConditionalOnMissingBean`; an extension module replaces one by declaring `@AutoConfiguration(before = DefaultsAutoConfiguration.class)` (or by being scanned).
- Restart-from-node re-runs the node and its descendants and replays the recorded output of the
  nodes that feed them (`RestartPlan`); independent branches are left alone.
- Set `yukta.restart.store-type=postgres` to keep node outputs in PostgreSQL (`R2dbcNodeCheckpointStore`; no caps; payloads come back as plain JSON values). With it and the PostgreSQL execution log,
  `ExecutionResumer.resume(executionId)` continues a run that a restart aborted: the log holds the
  payload (`RUN_STARTED`); nodes that finished (a durable end marker, below) replay their
  recorded output, the rest run again. `NODE_FINISHED` log events are history only. A run resumes once (`RUN_RESUMED`; the new run can be resumed in turn: `rerun` copies every finished node's output under the new id, and the payload counts the chain in `yukta.resumes`, which `TakeoverRecovery` caps at `MAX_AUTO_RESUMES` = 3 and `StartOptions` strips from client payloads) against the version it started
  with (the current one, or a saved version whose prepared graph has the same `DefinitionVersion`). REST: `POST /api/executions/{id}/recover` (409 `ConflictException` if the run was not
  aborted, was already resumed or none of the last 20 saved versions has the graph it ran); MCP: `control_workflow` action `RECOVER`.
  Only one resume per run is accepted (the claim is kept in memory and dropped on failure). The end
  of a node is a durable marker kept with its output (`NodeCheckpointStore.markComplete`, written before the completion
  goes on downstream; `yukta.checkpoint.errors` counts markers that failed, and that node then runs
  again). A resume skips only nodes with such a marker. What remains is the instant between a
  plugin's side effect and the marker, so make plugins idempotent on the message `id`.
- Restart-from-node on a run that is still going replays only parents that have finished (the
  durable end marker); a replayed output is marked finished under the new run, so a second resume
  does not run those nodes again. A resume claim is dropped if the caller cancels.
- Run starts and ends go to an `ExecutionLogStore`. By default it is in memory and trimmed (`LogRetention`: the newest `retained-runs`, never a run without an end, every `compact-after` events); `yukta.session.store-type=postgres` keeps it in PostgreSQL. On startup `ExecutionRecovery`
  marks runs that never finished as `ABORTED`, restores the newest
  `yukta.execution-log.retained-runs` (default 500) into history, and calls
  `ExecutionLogStore.applyRecovery` (drop the older runs, trim scheduler entries, append the abort
  events; PostgreSQL does it in one transaction and writes only the difference). The log is compacted to the newest `retained-runs` runs after every
  `yukta.execution-log.compact-after` (default 10000) events. Compaction and recovery keep the
  newest runs by their last event, and compaction never drops a run without an end.
