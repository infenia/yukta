# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited

# Core Module — Re-review, DAG Pitfall Analysis & Target Design

> **Round 2** re-review on 2026-10-10 against `main` @ `a26f3dbd` (≈13.3k main lines, 93 test classes).
> Round 1 (2026-10-09, @ `8d3161b5`) found 36 issues, and most were fixed in commits `4f09c98f` through `c733a3f4`.
> The full round-1 write-up is in git history: `git show a26f3dbd:core/docs/core-improvements.md`.
> Companion: [`core-module-guide.html`](core-module-guide.html) is the interactive guide: pitfall matrix,
> control model, node state machine, and a filterable improvement board.

**Goal this document serves:** a DAG-based orchestrator that is *efficient* (streaming, backpressured,
low overhead), *resilient* (survives slow nodes, failures and restarts, with no silent loss), and
*controllable* (rich, precise, acknowledged controls that don't fight the engine).

---

## 1. Verdict

Round 1 fixes landed well:
- The status model is typed, and control state is separate from lifecycle status.
- A stop is reported as `STOPPED`.
- Node broadcast uses `publish()` with real backpressure.
- The virtual-thread scheduler is fixed, and dead control code is gone.
- Validation, restart lookup, the plugin registry, history caps and atomic config writes are hardened.

The streaming core is now sound.

The second pass looked at **time, failure and control semantics**, which a streaming DAG gets wrong
most easily. It found five problems, each reproduced against the real classes with a throwaway probe
test (see §3):

1. **Timeouts are measured from subscription, not from work.** Every node subscribes at start, so a
   node's 30 s default idle timeout includes the time spent *waiting for its upstream*. Any workflow
   with a step longer than 30 s fails, including `PROCESS_EXECUTOR` (its own default is 300 s) and
   `LLM_CALL` (120 s), unless every downstream node sets `timeoutSeconds`. Pausing has the same
   effect: a pause longer than a downstream node's timeout fails the run.
2. **Node outcomes are incomplete.** Nodes cancelled by a stop or a sibling failure stay `RUNNING`.
   Nodes that never ran stay `PENDING`. There's no `CANCELLED`, `SKIPPED`, `UPSTREAM_FAILED` or
   `TIMED_OUT`.
3. **Restart-from-node is broken in four ways.**
   - Checkpoints are keyed by the wrong id, so the restart node receives no input.
   - It only works while the original run is *still running*, so a failed run can't be restarted.
   - Only the last message per node is kept.
   - The cut uses the topological index, so independent branches are re-run with empty input.
4. **Step mode loses credits.** Two `step` commands sent before an element arrives let **0**
   elements through. Pause gates the plugin's *output*, so up to 256 in-flight elements are still
   processed after a pause.
5. **Control is fire-and-forget.** REST returns 200 when a command is queued, not when it's applied.
   Commands in the same 50 ms batch are re-sorted by priority, and failures are only logged.

Structurally, the engine still lacks what mature orchestrators treat as basics:
- per-node retry policies;
- error-routing and join trigger rules;
- a durable, ordered execution event log, needed for crash recovery, replay and an exact audit trail;
- admission control and concurrency limits;
- graceful drain;
- definition versioning;
- metrics and tracing.

### Scorecard (round 1 → round 2)

| Area | R1 | R2 | Notes |
|---|---|---|---|
| Streaming and backpressure | 3 | **4.5** | `publish()` broadcast connects once all edges subscribe. Fan-out and fan-in are correct |
| Status model | 2 | **3.5** | Typed and split from control state. Node terminal states are still missing (R3) |
| Time semantics | — | **1.5** | Timeouts count upstream wait and pause time (R1, R2) |
| Failure semantics | 2.5 | **2.5** | Fail-fast only. No retry, error port or trigger rules (R8) |
| Control plane | 2 | **2.5** | Step is wired but loses credits. Skip rejected at runtime. No acks, reordering (R5, R9) |
| Restart and recovery | 2 | **1.5** | Restart-from-node is broken (R4). Nothing durable (R10) |
| Resource governance | 2 | **2.5** | Bounded scheduler. No admission or concurrency limits (R11) |
| Simplicity | 2.5 | **4** | Builders and dead code removed |
| Observability | 2 | **2** | Logs only (R16) |

---

## 2. Round-1 findings: status

All entries were checked in code at `a26f3dbd`.

| ID | Finding | Status |
|---|---|---|
| C1 | Control status strings crash the tracker | ✅ Fixed: typed `TaskStatus`, separate `ControlState` |
| C2 | Stop reported as SUCCESS | ✅ Fixed: `STOPPED` |
| C3 | Two owners of terminal status | ✅ Fixed: orchestrator only |
| C4 | Duplicate FAILURE | ✅ Fixed: `NodeStreams` |
| C5 | Runtime skip no-op | ⚠️ Rejected at runtime with a clear error. Skip is now *unavailable* (see R5 and the control model) |
| C6 | Step mode no-op | ⚠️ Wired to the valve, but credits are lost (R5) |
| C7 | Terminal timeout no-op | ⚠️ Applied, but with the wrong clock (R1) |
| C8 | Broadcast bypasses backpressure | ✅ Fixed: `NodeBroadcast` uses `publish(bufferSize)` |
| C9 | Shared plugin init/shutdown | ✅ Mitigated: no shutdown fan-out; stateless contract documented |
| C10 | NPE on a bad edge | ✅ Fixed |
| C11 | Unknown restart node restarts from 0 | ✅ Fixed (but see R4) |
| C12 | Duplicate plugin types | ✅ Fixed: fail fast |
| C13 | Tracker memory unbounded | ✅ Fixed: 100 finished runs per session |
| C14 | Unsafe publication | ✅ Fixed: `volatile` |
| C15 | Fire-and-forget execution, no graceful shutdown | ❌ **Open** (→ R11) |
| C16 | Non-atomic config writes | ✅ Fixed: temp file and `ATOMIC_MOVE` |
| C17 | Stopping twice throws | ✅ Fixed: `tryEmitEmpty` |
| C18 | Secret blacklist | ✅ Fixed: env/sys only, plus `EnvSecretProvider` |
| P1 | Fixed virtual pool | ✅ Fixed: `newBoundedElastic` on virtual threads (task queue is unbounded, see R11) |
| P2 | Idle heartbeat timers | ✅ Deleted |
| P3 | Payload log echo | ✅ Capped at 2 KB |
| P4 | Checkpoint per message (`flatMap`) | ⚠️ `concatMap` (ordered). Still only the last message is kept (R4) |
| P5 | Busy-loop sinks | ❌ Open: `busyLooping(100ms)` is still in the tracker and control bus |
| P6 | O(N·E) compile scan | ❌ Open (low) |
| S1–S6, S8–S10 | Typing, dedupe, builders, dead code, docs | ✅ Fixed |
| S7 | Logging volume | ❌ Open (low) |
| T1–T3, T5 | Tests | ✅ Added (backpressure and status tests) |
| T4, T6 | Step/skip end-to-end, concurrent same-type plugins | ❌ Open |

---

## 3. New findings (round 2)

Severity: **P0** breaks normal workflows · **P1** wrong results or feature broken · **P2** should fix ·
**P3** clean-up. ✔ means it was reproduced against the real classes with a throwaway probe test (since
deleted). The probe printed the results quoted below.

### R1 · P0 · Node timeouts include upstream wait time ✔
- **Where:** `NodeStreams.withTimeoutAndTracking` → `source.timeout(timeout)` on every processor's
  output. `TerminalNodeAssemblerStrategy` → `safeInput.timeout(timeout)`. The default is
  `Plugin.getDefaultTimeout()` = 30 s, and **no plugin overrides it**.
- **Mechanism:** Every node subscribes when the run starts (the connectors run before any data
  flows). `Flux.timeout(d)` also limits the wait for the *first* element. A downstream node's first
  output can't appear before its upstream finishes, so its clock is running the whole time.
- **Probe:** a node with a 1 s timeout over an upstream that takes 2 s → `TimeoutException` at 1 s.
- **Impact:** Any workflow with a step longer than 30 s fails, even though `PROCESS_EXECUTOR`
  advertises a 300 s timeout and `LLM_CALL` 120 s. The run is reported as `FAILURE` with a
  misleading timeout on the wrong node.
- **Fix (timer model, see §5.3):**
  - **Processing timeout:** time from receiving an input element to the matching output (or
    completion). Use `flatMap`/`concatMap` per element with `.timeout(...)` for per-message plugins.
    For stream-shaped plugins, start the clock on the *first input*.
  - **Idle timeout:** off by default. When set, it starts after the first input.
  - **Node deadline:** the total wall-clock limit for a node, counted from its first input.
  - Plugins declare realistic defaults: process-executor uses its configured `timeout` + 10 %;
    llm-call uses its `timeout × retries`.

### R2 · P1 · Pause counts against timeouts ✔
- **Where:** Pause (`ReactiveControlValve`, `filterWhen`) holds elements. Downstream `.timeout()` and
  the run-level `ExecutionResources` `timeout(sessionTimeout)` keep counting.
- **Probe:** a paused parent made its child time out after 1 s.
- **Impact:** Any pause longer than the smallest downstream timeout fails the workflow, so pause is
  unsafe to use.
- **Fix:** Pause-aware clocks. Timeouts subtract time spent paused, or are suspended while
  `globalPauseValve` or an upstream valve is closed. Simplest: the run's `ControlState` exposes
  `pausedNanos`, and timeout operators use a custom `Mono.delay` that waits again while paused.

### R3 · P1 · Node outcomes are never finalised ✔
- **Where:** `NodeStreams` emits `RUNNING` (subscribe), `SUCCESS` (complete) and `FAILURE` (error)
  only. There's no `doOnCancel`. `finishWorkflow` doesn't sweep node states. `TaskStatus` has
  `PENDING, RUNNING, SUCCESS, FAILURE, ERROR, STOPPED`.
- **Probe:** after the subscription was cancelled, the only status emitted was `RUNNING`.
- **Impact:** After a stop, a timeout or a sibling failure, the UI shows nodes running forever.
  Nodes that never received input stay `PENDING` (they should be `SKIPPED`/`UPSTREAM_FAILED`).
- **Fix:** A node state machine (§5.2): `doOnCancel` → `CANCELLED`, timeout → `TIMED_OUT`, upstream
  failure → `UPSTREAM_FAILED`, no input on any edge → `SKIPPED`. A final sweep in `runTracked`
  marks any non-terminal node from the run's outcome.

### R4 · P1 · Restart-from-node is broken in four ways ✔ (keying)
1. **Wrong checkpoint key.** `RestartFromNodeCommandProcessor` does
   `collectMap(Message::getSourceNodeId, …)`. A checkpoint saved by node B has
   `sourceNodeId = B's parent`, because edge routing stamps the *incoming* edge's source and
   plugins keep it. The orchestrator looks the checkpoint up by B's id, finds nothing, and replays
   `Flux.empty()`.
   - **Probe:** the checkpoint saved by B had `sourceNodeId=A`.
   - The unit test mocks `getSourceNodeId()` to return the parent id, which hides the bug.
   - **Fix:** key by the `parentNodeId` already in scope.
2. **Only running executions can be restarted.** The processor looks up
   `registry.findByExecutionId(...)`, but finished runs are unregistered. `runTracked.doFinally`
   also clears their checkpoints. The most common reason to restart, *a failed run*, is
   impossible.
3. **Only the last message per node is kept.** `NodeCheckpointStore` overwrites the entry, so a
   splitter's or trigger's earlier messages are lost on restart.
4. **The cut uses topological index, not ancestry.** Every node before the restart index is
   bypassed and every node after it is re-run. Independent branches after the index run again
   (repeating side effects) and get `Flux.empty()` from their bypassed parents.
- **Fix:** see §5.4: a durable per-edge output log kept for N days, and a restart plan computed as
  *descendants(restartNode)* plus boundary edges replayed from the log. This is how Airflow's
  "clear downstream" and Temporal's reset work.

### R5 · P1 · Step mode loses credits; pause leaks in-flight work ✔ (step)
- **Where:** `ReactiveControlValve.allowPassage` captures `stepCounter` when each element arrives
  and waits for a *greater* value. Steps sent earlier are not banked.
- **Probe:** with steps requested = 2, elements passed = 0.
- **Also:** Valves run in `applyPostProcessingControls` (after the plugin) with `filterWhen`, which
  prefetches 256 elements. So "pause" lets the plugin keep processing, with its side effects, until
  the prefetch is used up. Skip is rejected at runtime, so there's no way left to bypass a node.
- **Fix:** The valve becomes a **permit counter** (`AtomicLong credits`; `step(n)` adds n, `resume`
  sets it to unbounded). Gate the **input** with a demand-limited operator (`limitRate(1)` plus a
  `Mono` that waits for a permit). Pause then stops *pulling* (true backpressure), and step `n`
  releases exactly n elements.

### R6 · P1 · Task and workflow events are not ordered relative to each other
- **Where:** `DefaultTaskTrackerService` has separate `taskStatusSink`, `wfStatusSink` and `logSink`,
  each batching independently (`bufferTimeout(100, 50ms)` on `Schedulers.parallel()`).
  `getStatusStream` ends at the first terminal workflow event (`takeUntil`).
- **Impact:** The terminal workflow event can be processed before the last node's `SUCCESS`, so
  the SSE stream closes and clients never see the final node state. The run then looks finished
  with a node still running. Log lines can also arrive after the stream closes.
- **Fix:** A single per-execution **ordered event log** (`seq`, `type`, `nodeId`, `status`, `ts`)
  that every producer appends to, with one consumer. Streams end only after the `RUN_FINISHED` event
  *and* everything before it have been delivered. This is also the basis for R10.

### R7 · P1 · "Safe stop" is a hard cancel
- **Where:** `runTracked` → `Mono.firstWithSignal(execution, safeStopSink → STOPPED)`. When the stop
  wins, the whole execution subscription is cancelled at once. `StopWorkflowCommand` documents "safe
  stop: inflight work drains".
- **Impact:** In-flight work (a running process, an HTTP call) is killed mid-way, and there's no
  drain option.
- **Fix:** Three explicit modes (§5.1):
  - `DRAIN`: stop the triggers, let in-flight elements finish, with a deadline;
  - `CANCEL`: cancel subscriptions, letting plugins clean up;
  - `KILL`: dispose immediately.
  The default is `DRAIN` with a 30 s deadline, then `CANCEL`.

### R8 · P1 · Failure handling is fail-fast only
- **Where:** `Flux.merge` in `mergeParentStreams` errors (and cancels siblings) on the first parent
  error. There's no engine-level retry. Retries exist only inside individual plugins. There's no
  error port, no join trigger rules, and no compensation.
- **Impact:** One flaky HTTP call fails a 40-node run. Fan-in can't express "continue if one branch
  fails". A branch failure can't be handled downstream.
- **Fix (§4 pitfalls 7–9):** Per-node `retry` policy (max attempts, exponential backoff with jitter,
  retryable error classes; `Retry.backoff(...)`), plus per-node `onError` of
  `FAIL | CONTINUE | ROUTE(port "error")`, plus per-join `trigger` of
  `ALL_SUCCESS | NONE_FAILED_MIN_ONE_SUCCESS | ALL_DONE | ONE_SUCCESS`. Use `Flux.mergeDelayError`
  where the trigger rule tolerates failures.

### R9 · P1 · Control commands aren't acknowledged, ordered or idempotent
- **Where:** `ControlBusService.emit` succeeds when the command is queued, so REST returns 200
  before anything is applied. `handleControlBatch` **sorts each batch by priority**, which reorders
  commands sent within 50 ms of each other, for example `pause` and then `resume`. Processor errors
  (such as "node not pausable") are logged and swallowed. The gateway's "execution exists" check
  happens before the command is queued and isn't re-checked when it's applied.
- **Fix:** A per-execution serialised command queue (`groupBy(executionId).concatMap(...)`, keeping
  the order in which commands arrived). Requests carry a `commandId` (idempotency key). The caller
  gets a `CommandResult` (`APPLIED | REJECTED(reason) | NOOP`) once the command is applied, with a
  timeout. Every command and result is appended to the execution event log (R6) for audit.

### R10 · P1 · Nothing is durable, so a crash loses every running and finished run
- **Where:** The tracker, `ExecutionControlRegistry`, checkpoints, status history and (by default)
  definitions and sessions are all in memory.
- **Impact:** After a JVM restart, running executions vanish with no record, and history is gone.
  You can't resume, audit or debug.
- **Fix:** An append-only execution event log (§5.5). Start with an embedded store (SQLite or H2 in
  file mode, or JSONL per execution). On boot, recovery marks orphaned runs `ABORTED`. Phase 2 adds
  optional resume from the last checkpoint.

### R11 · P2 · No admission control, concurrency limits or graceful shutdown (round-1 C15)
- **Where:** `DefaultControlBusGateway.prepareAndExecute` does `execute(...).subscribe()` and drops
  the `Disposable`. The virtual scheduler's task queue is `Integer.MAX_VALUE`. There's no limit per
  workflow, session or server, no queue, and no priority. Concurrent starts of an uncached workflow
  prepare it twice (cache stampede).
- **Fix:**
  - An `ExecutionScheduler` with a global semaphore plus per-workflow `maxConcurrentRuns` and a
    `QUEUE | REJECT | REPLACE` policy;
  - `PENDING → QUEUED → RUNNING` statuses;
  - keep the `Disposable` and drain in-flight runs in a `SmartLifecycle.stop()`;
  - `Mono.cache()` the prepare per key to stop the stampede.

### R12 · P2 · Validator gaps
- **Duplicate `nodeId`s** pass validation, then fail in the topological sort with the misleading
  message "contains cycles or is invalid".
- **Duplicate edges** (A→B twice) are accepted, and B then receives every message twice.
- **Edge `sourcePort`** isn't checked against the source plugin's `getOutputPorts(config)`. A typo
  creates a silently dead edge: the downstream node completes with no input.
- There are no limits on graph size (nodes, edges, fan-out), which is a denial-of-service risk.
- `hasCycles` uses recursive DFS (a `StackOverflowError` on deep graphs) and duplicates Kahn's
  algorithm in `TopologicalSortService`.
- **Fix:** Add explicit checks with precise messages. Use the Kahn result for the cycle check.

### R13 · P2 · The disposables list isn't thread-safe
- **Where:** The `disposables` list is an `ArrayList`. `NodeBroadcast.subscribe` adds to it on the
  subscriber's thread (possibly a virtual thread from `subscribeOn`), while
  `ExecutionResources.dispose` copies it.
- **Impact:** A rare `ConcurrentModificationException` or leaked connections on cancel.
- **Fix:** `Disposables.composite()` (thread-safe, and disposes late additions).

### R14 · P2 · No definition versioning
- Executions don't record which definition they ran. `PreparedWorkflowCache` replaces definitions
  in place, so history, restart and debugging can silently use a different graph.
- **Fix:** Store a content hash or version on each execution, and restart against the same version.

### R15 · P2 · Starts aren't idempotent
- A client that retries `POST /workflow/start` gets two runs.
- **Fix:** Accept an `Idempotency-Key` and return the existing `executionId` for a repeated key
  within a time window.

### R16 · P3 · No metrics or tracing
- There are no Micrometer timers or counters per node (latency, throughput, queue depth,
  backpressure wait) and no OpenTelemetry spans. Reactor context propagation is already enabled, so
  wiring this is cheap.

### R17 · P3 · Remaining round-1 items
- P5: busy-loop sinks. Moot after R6, which uses a single ordered log with `tryEmitNext` and
  counted drops.
- P6: O(N·E) compile.
- S7: log volume.
- 11 coverage exceptions remain.

---

## 4. R&D: DAG orchestration pitfalls and how Yukta measures up

This is a survey of the failure modes mature orchestrators design for: Airflow, Temporal, Argo
Workflows, Flink, Dagster and Prefect. Each row gives the pitfall, the established solution,
Yukta's state today, and the recommendation.

| # | Pitfall | How leading systems handle it | Yukta today | Recommendation |
|---|---|---|---|---|
| 1 | **Cycles and malformed graphs** | Reject at definition time with precise errors (all) | ✅ Cycles, unknown nodes, categories. ❌ duplicate ids or edges, port names, size limits (R12) | Complete validation (R12) |
| 2 | **Join after a branch never fires** (the classic dead-path problem: an unchosen branch is *skipped*, so an `all_success` join waits forever or is skipped) | Airflow *trigger rules*; recommends `none_failed_min_one_success` after branches; skip propagation | ✅ No hang: streaming `merge` completes when all parents complete, even if a port filter passed nothing. ❌ No way to say "require at least one success" or "tolerate failure" | Per-join trigger rules (R8); emit `SKIPPED` for nodes that received nothing (R3) |
| 3 | **Diamond / fan-out to a slow child** (fast child starved or memory blow-up) | Flink credit-based flow control; bounded buffers | ✅ `publish(bufferSize)` limits the parent to the slowest child | Expose per-node queue depth metrics (R16) |
| 4 | **Late subscriber misses data** (hot streams) | Barrier or "all consumers ready" before the source starts | ✅ `NodeBroadcast` connects when all expected edges have subscribed | Keep it, with a test for fan-out to a processor and a terminal |
| 5 | **Timeout semantics** (queue wait counted as work; pause counted as time) | Separate schedule-to-start, start-to-close and heartbeat timeouts (Temporal); Argo `activeDeadlineSeconds` per step | ❌ Idle timeout from subscription, counts upstream and pause time (R1, R2) | Processing, idle and deadline timers that start on first input and are pause-aware |
| 6 | **Long-running work looks dead** (no liveness signal vs a hung task) | Heartbeats with a heartbeat timeout (Temporal activities) | ❌ No plugin heartbeat API (idle timeout is a crude stand-in) | `PluginContext.heartbeat(details)` resets the idle clock; progress is shown in the UI |
| 7 | **Transient failure fails the whole run** | Retry policy with exponential backoff, cap and jitter (Argo `retryStrategy`, Temporal `RetryPolicy`, Airflow `retries`) | ❌ Engine has no retries | Per-node retry policy (R8) |
| 8 | **Retries duplicate side effects** | Idempotency keys, at-least-once plus idempotent activities (Temporal), exactly-once via barrier snapshots (Flink) | ❌ No idempotency guidance or keys | Stable `messageId` plus `attempt` header. Plugins dedupe on `(executionId, nodeId, messageId)`. Document at-least-once semantics |
| 9 | **Fail-fast vs partial results** | Configurable: `fail_fast`, `all_done`, continue-on-failure, error handlers | ❌ Fail-fast only | `onError: FAIL / CONTINUE / ROUTE(error)` per node (R8) |
| 10 | **Crash loses state** | Event-sourced history replayed on recovery (Temporal); periodic checkpoints (Flink) | ❌ All in memory (R10) | Append-only execution log, recovery marks runs `ABORTED`, then resume (§5.5) |
| 11 | **Re-running part of a graph** | Airflow "clear task + downstream"; Temporal reset to an event; Dagster re-execute from failure | ❌ Broken (R4) | Descendant-subgraph restart with replayed boundary inputs (§5.4) |
| 12 | **Definition changes under running or replayed runs** | Versioning and patch markers (Temporal), pinned versions | ❌ None (R14) | Content-hash version per run |
| 13 | **Unbounded concurrency / noisy neighbour** | Argo `parallelism`, semaphores and mutexes; Airflow pools; Prefect concurrency limits | ❌ None (R11) | Admission scheduler, per-workflow and global limits, resource-tag semaphores |
| 14 | **Cancellation doesn't reach running work** | Cooperative cancellation plus heartbeat; graceful then forced | ⚠️ Reactive cancel reaches subscribers. Process plugin kills only its direct child (plugins G9). No drain (R7) | Drain, cancel and kill modes; plugins get a cancellation hook |
| 15 | **Control races and reordering** (pause and resume reordered, double stop) | Signals serialised per workflow (Temporal signals are ordered in history) | ❌ Priority sort reorders, no acks (R9) | Per-execution FIFO command queue with acks and idempotency |
| 16 | **Observability gaps** (can't tell why something is slow or stuck) | Per-task metrics, traces, UI timelines | ⚠️ Status and logs only (R16) | Micrometer and OpenTelemetry with backpressure-wait metrics |
| 17 | **Poison message blocks a stream** | Dead-letter queues, error ports | ❌ An error fails the node | `ROUTE(error)` plus a dead-letter terminal, using the existing `withFailure` headers |
| 18 | **Status/event ordering** (UI shows a finished run with running nodes) | A single ordered history per execution | ❌ Three independent sinks (R6) | One ordered per-run event log |
| 19 | **Unbounded memory per run** (big payloads in every buffer and checkpoint) | Claim-check pattern; bounded buffers | ⚠️ Bounded buffers; payload echo capped at 2 KB; `ClaimCheckStore` SPI unused | Claim check for payloads over N KB |
| 20 | **Starvation and priority** | Priority queues and weights (Airflow `priority_weight`) | ❌ None | Priority on queued runs (R11) |

---

## 5. Target design

### 5.1 Control model: rich, precise, acknowledged

Every command goes through one per-execution FIFO queue. It's idempotent (keyed by `commandId`),
returns a `CommandResult`, and is recorded in the execution log.

| Command | Semantics (target) | Mechanism | Today |
|---|---|---|---|
| **Pause run / node** | Stop *pulling* new input. In-flight elements finish. Timers are suspended | Input-side permit gate (true backpressure); pause-aware timers | Output-side `filterWhen` (256 leak); timers keep running |
| **Resume** | Release the gate | Permits = ∞ | ✅ |
| **Step n (node)** | Let exactly n input elements through, then pause | `credits += n` | Credits lost (R5) |
| **Breakpoint** (new) | Pause automatically when a predicate matches an element at a node | Predicate on the input gate (SpEL, sandboxed) | — |
| **Skip node** | Pass input through unchanged, *or* mark `SKIPPED` and propagate per trigger rules | Pre-start parameter `skipNodes`; runtime: the gate switches to pass-through *between* elements | Rejected at runtime |
| **Stop run** | `DRAIN` (default, with a deadline) → `CANCEL` → `KILL` | Stop triggers; `takeUntil(deadline)`; dispose | Hard cancel only (R7) |
| **Stop node** | `DRAIN` or `CANCEL` a single node; downstream sees completion | ✅ safe and immediate sinks | ✅ |
| **Retry node** (new) | Re-run a failed node in a *still-running* run with its recorded input | Replay input from the edge log | — |
| **Restart run** | New run, same definition version and same payload | ✅ | ✅ (pins no version) |
| **Restart from node** | New run: descendants of X only; boundary inputs replayed | Edge-log replay (§5.4) | Broken (R4) |
| **Set concurrency / priority** (new) | Change a queued or running run's limits | Admission scheduler | — |
| **Inject message** (new, debug) | Push a message onto an edge | Edge log append plus a hot source | — |

### 5.2 Node state machine

```
PENDING ──first input or trigger start──▶ RUNNING ──complete──▶ SUCCESS
   │                                │ ├─error (retries left)──▶ RETRYING ──▶ RUNNING
   │                                │ ├─error (exhausted)────▶ FAILURE ──onError=ROUTE──▶ (error port)
   │                                │ ├─timeout──────────────▶ TIMED_OUT
   │                                │ ├─pause────────────────▶ PAUSED ──resume──▶ RUNNING
   │                                │ └─cancel / stop────────▶ CANCELLED
   ├─all inputs complete, none received──▶ SKIPPED
   ├─upstream failed and the trigger rule isn't met──▶ UPSTREAM_FAILED
   └─run finished before the node started──▶ CANCELLED
```

The run state is derived from the node states plus the stop mode:
`QUEUED → RUNNING → SUCCESS | FAILURE | STOPPED | TIMED_OUT | ABORTED (crash)`.

### 5.3 Time model

| Timer | Starts | Pauses | Default | Owner |
|---|---|---|---|---|
| Queue wait | Admission | — | No limit, but reported | Scheduler |
| Node processing (per element) | Element received | Yes | Plugin-declared | Engine, per element |
| Node idle | First input / last output | Yes | **Off** | Engine |
| Node deadline | First input | Yes | None | Engine |
| Plugin heartbeat | Each `heartbeat()` call | Yes | Off | Plugin |
| Run deadline | RUNNING | Yes | Session `execution-timeout-seconds` | Orchestrator |

### 5.4 Restart and replay

- Persist every node's **outputs per edge** (or a claim-check reference for large payloads) to the
  execution log, keyed by `(executionId, nodeId, seq)`. Keep them for a retention window, not just
  while the run is alive.
- **Restart-from-X plan:**
  - re-run `descendants(X) ∪ {X}`;
  - for every edge from outside that set into it, replay the recorded messages in their original
    order;
  - don't touch nodes outside the set.
  - This works for finished, failed and running source runs.
- The new run records `restartOf = (executionId, X, definitionVersion)`.

### 5.5 Durable execution log (the backbone for R6, R9, R10 and R4)

```
execution_event(seq, execution_id, ts, type, node_id, status, attempt, command_id, data)
types: RUN_QUEUED, RUN_STARTED, NODE_STARTED, NODE_OUTPUT(ref), NODE_FINISHED(status),
       NODE_RETRY, CONTROL_REQUESTED, CONTROL_APPLIED, CONTROL_REJECTED, RUN_FINISHED, LOG(ref)
```

- **Single writer per execution:** one `Sinks.many().unicast()` with one ordered consumer, batched
  writes.
- **Projections:** the tracker's `WorkflowProgress`, the SSE streams, history and audit are all
  *read models* of this log, so ordering is guaranteed and a stream ends after it has delivered
  `RUN_FINISHED`.
- **Storage:** a pluggable `ExecutionLogStore`. Start with in-memory and JSONL file implementations,
  then SQLite or Postgres. The append path stays off the hot path through batching.

### 5.6 Efficiency budget

| Area | Target |
|---|---|
| Per-message overhead per node | ≤ 2 allocations beyond the plugin's (history stamp optional, checkpoint by reference) |
| Broadcast | `publish(bufferSize)` (done) plus `limitRate` on fan-in |
| Event and log path | Batched append, `tryEmitNext` with counted drops for *log* lines only, never for state |
| Prepare | Cached per `(definitionHash)` with stampede protection |
| Benchmark | A JMH or `StepVerifier` throughput test: a 20-node linear DAG and a 10-way diamond with 100k messages, run in CI to catch regressions |

---

## 6. Roadmap

| Phase | Items | Outcome | Acceptance test |
|---|---|---|---|
| **1. Correct time** (1–2 days) | R1, R2 | Long steps and pauses no longer fail runs | A 60 s process step followed by a terminal succeeds with default config; a 2-minute pause, then resume, succeeds |
| **2. Truthful state** (2 days) | R3, R6 | Every node ends in a terminal state; ordered stream | After a stop, no node is RUNNING; the SSE stream's last node event comes before RUN_FINISHED |
| **3. Precise control** (2–3 days) | R5, R9, R7 | Pause is backpressure; step is exact; acknowledged FIFO commands; drain stop | `step 2` passes exactly 2; `pause` then `resume` sent within 10 ms ends running; stop drains in-flight work |
| **4. Failure semantics** (3 days) | R8, pitfalls 8, 9, 17 | Retries, error ports, trigger rules, dead-letter queue | A flaky node retried 3× succeeds; a branch failure routed to an error port while the join completes |
| **5. Durability and restart** (4–5 days) | R10, R4, R14 | Execution log, crash recovery, correct restart-from-node for finished runs, versions | Kill the JVM mid-run: on restart the run is `ABORTED` with full history; restart-from-node of a FAILED run replays the boundary inputs |
| **6. Governance** (2 days) | R11, R15, pitfall 20 | Admission, limits, priority, idempotent start, graceful shutdown | 100 starts with a limit of 5 → 5 running, 95 queued; SIGTERM drains |
| **7. Observability and polish** (2 days) | R16, R12, R13, R17 | Metrics, traces, complete validation | Grafana shows per-node p95 and backpressure; duplicate-id and bad-port errors are precise |

---

## 7. Sources (R&D)

- Airflow trigger rules and the branch/join pitfall: [Astronomer — Airflow trigger rules](https://www.astronomer.io/docs/learn/airflow-trigger-rules), [visual guide](https://astronomer.io/blog/understanding-airflow-trigger-rules-comprehensive-visual-guide), [Airflow nested-branch example](https://airflow.apache.org/docs/apache-airflow/3.0.4/_modules/airflow/example_dags/example_nested_branch_dag.html)
- Durable execution, event history and replay: [Temporal — Event History (Java)](https://docs.temporal.io/encyclopedia/event-history/event-history-java), [Temporal event sourcing architecture](https://www.mintlify.com/temporalio/temporal/architecture/event-sourcing), [replay failures with new code](https://hackernoon.com/when-old-workflows-meet-new-code-preventing-temporal-replay-failures-in-production)
- Checkpointing under backpressure: [Flink — From aligned to unaligned checkpoints](https://flink.apache.org/2020/10/15/from-aligned-to-unaligned-checkpoints-part-1-checkpoints-alignment-and-backpressure/), [Flink checkpointing docs](https://ci.apache.org/projects/flink/flink-docs-master/docs/dev/datastream/fault-tolerance/checkpointing/)
- Retries, backoff and concurrency limits: [Argo Workflows — retries](https://argo-workflows.readthedocs.io/en/latest/retries/), [Argo 3.7 new features](https://argo-workflows.readthedocs.io/en/release-3.7/new-features/)
