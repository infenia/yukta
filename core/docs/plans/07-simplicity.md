# 07 Simplicity: split the two 1000-line classes

Goal: no class over ~500 lines in `core`, with no behaviour change. One PR per class, each green on `:core:check` (JaCoCo needs 100% per class, so every extracted class must be covered by existing tests through the facade; add a test only for a branch that was unreachable before).

Sizes today: `DefaultTaskTrackerService` 1066, `DefaultControlBusGateway` 991, `ExecutionScheduler` 609, `WorkflowValidator` 521, `ControlBusService` 504, `WorkflowOrchestrator` 496, `FileSessionConfigStore` 491. Only the first two are worth the churn now; the rest are near the line and can wait.

## PR A: `DefaultTaskTrackerService` (1066 → about 450)
It already delegates run state to `WorkflowState` and telemetry to `RunTelemetry`. What remains mixes five jobs. Extract, package-private in `orchestrator/tracker`:
1. `TrackerEventLoop`: `eventSink`, `pendingLogs`/`MAX_PENDING_LOGS`, `publish`, the batching consumer, `handleEvents`/`applyIsolated`. Takes the apply functions as a callback so it knows nothing about state.
2. `ExecutionStreams`: `logSinks`, `statusSinks`, `logFeed`, `getLogStream`, `getStatusStream`, `getLogFlux`, `notifyStatusChange`, sink cleanup (the Reactor-facing half).
3. `ExecutionHistory`: `finishedBySession`, `trimHistory`, `getHistory`, `getActiveSessions`, `removeSession`, cleanup TTL scheduling.
4. Keep in the service: the public `TaskTrackerService` API, `applyTask`/`applyWorkflow`/`applyLog` (state transitions), index maps (`sessionStates`, `executionIndex`, `latestExecs`).
Order: 3 (least coupled), then 2, then 1. Each step is its own commit.

## PR B: `DefaultControlBusGateway` (991 → about 450)
It is a facade whose methods group cleanly by the section comments already in the file. Extract:
1. `RestartCoordinator`: `pendingRestarts`, `restartWorkflow`, `restartFromNode`, `completeRestartSuccess/Failure` (and `RestartCompletionSink` moves with it).
2. `NodeControlOperations`: `pauseNode/resumeNode/skipNode/step*/breakpoints/retryNode/requireNodeControl`.
3. `ExecutionQueries`: the observability and state-query section (`watchLogs`, `getProgress*`, `getHistory`, `getLastHeartbeat/Statistics`, `getActiveNodes`).
4. Keep in the gateway: plugin registration, configuration/preparation, workflow-level pause/resume/stop, `executeCommand`, and one-line delegations so `ControlBusGateway` (and every caller in `web` and `mcp`) is unchanged.

## Guardrails
- No public API change: `TaskTrackerService` and `ControlBusGateway` signatures stay as they are, so `web` and `mcp` are untouched.
- Move code verbatim first; clean up in a separate commit. Do not change locking, ordering or sink semantics (the tracker's ordering and "never drop state events" rules in `core/CLAUDE.md` are the contract).
- Constructors: the gateway and tracker are Spring beans built by tests directly; keep their current constructors and build the helpers inside, so the 3000+ lines of existing tests keep compiling.

## Verification per PR
- Tier 2 while iterating: `./gradlew :core:test --tests 'com.infenia.yukta.service.orchestrator.tracker.*'` (A) or `'com.infenia.yukta.service.control.*'` (B), then `:core:checkstyleMain :core:pmdMain :core:spotbugsMain` once after each extraction.
- Final: `./gradlew spotlessApply :core:check` in the background.
- Update the package table and line stats in `core-module-guide.html` and the tracker/gateway notes in `core/CLAUDE.md`.

## Done when
No `core` class is over about 600 lines, `:core:check` is green, and the guide stats match.
