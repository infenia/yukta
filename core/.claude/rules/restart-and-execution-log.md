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
  them makes a restart that needs its output refused rather than replayed partially, unless
  `yukta.restart.spill-dir` is set: then the whole output of that node moves to files there
  (`FileNodeCheckpointStore`, so it replays as plain JSON values), is deleted with the execution,
  and leftovers of an earlier process are removed at startup. Do not point it at a shared folder.
- **Plus split:** `FileSessionConfigStore`, `FileWorkflowDefinitionStore` and `JsonlExecutionLogStore` live in the AGPL `plus` module (same packages). `FileNodeCheckpointStore` stays here (the in-memory store spills to it); `PlusConfiguration` registers it as the durable store. `FeatureGateEnvironmentPostProcessor` stops start-up when a setting needs a module that is not in the build (file stores, PostgreSQL, HA, workers): to gate another setting add a row to its `GATES` list. `DefaultsAutoConfiguration` registers the in-memory execution log, in-memory checkpoints and `SingleServerLeader` with `@ConditionalOnMissingBean`; an extension module replaces one by declaring `@AutoConfiguration(before = DefaultsAutoConfiguration.class)` (or by being scanned).
- Restart-from-node re-runs the node and its descendants and replays the recorded output of the
  nodes that feed them (`RestartPlan`); independent branches are left alone.
- Set `yukta.restart.dir` to keep node outputs on disk (`FileNodeCheckpointStore`, one JSON line per
  message, appended through one open channel per node until `markComplete`, no caps; payloads come back as plain JSON values). With it and `yukta.execution-log.dir`,
  `ExecutionResumer.resume(executionId)` continues a run that a restart aborted: the log holds the
  payload (`RUN_STARTED`); nodes that finished (a durable end marker, below) replay their
  recorded output, the rest run again. `NODE_FINISHED` log events are history only. A run resumes once (`RUN_RESUMED`) and only if its definition
  is unchanged. REST: `POST /api/executions/{id}/recover` (409 `ConflictException` if the run was not
  aborted, was already resumed or its workflow changed); MCP: `control_workflow` action `RECOVER`.
  Only one resume per run is accepted (the claim is kept in memory and dropped on failure). The end
  of a node is a durable marker kept with its output (`NodeCheckpointStore.markComplete`: the file
  store forces the output, writes and forces `<node>.done` and the directory before the completion
  goes on downstream; `yukta.checkpoint.errors` counts markers that failed, and that node then runs
  again). A resume skips only nodes with such a marker. What remains is the instant between a
  plugin's side effect and the marker, so make plugins idempotent on the message `id`.
- Restart-from-node on a run that is still going replays only parents that have finished (the
  durable end marker); a replayed output is marked finished under the new run, so a second resume
  does not run those nodes again. A resume claim is dropped if the caller cancels.
- Run starts and ends go to an `ExecutionLogStore`. By default it is in memory and trimmed like the file log (`LogRetention`: the newest `retained-runs`, never a run without an end, every `compact-after` events); set
  `yukta.execution-log.dir` to keep `executions.jsonl` on disk. On startup `ExecutionRecovery`
  marks runs that never finished as `ABORTED`, restores the newest
  `yukta.execution-log.retained-runs` (default 500) into history, and rewrites the log without
  older ones. File access runs on one background writer thread, and the file is compacted to the
  newest `retained-runs` runs after every `yukta.execution-log.compact-after` (default 10000) events.
  Compaction and recovery keep the newest runs by their last event, and compaction never drops a
  run without an end. Events are not `fsync`ed unless `yukta.execution-log.fsync=true` (one force per
  event), so otherwise a power loss can lose the last few; a rewritten file (compaction, session
  config) is forced to disk before it replaces the old one.
