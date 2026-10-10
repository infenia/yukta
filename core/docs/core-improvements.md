# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited

# Core Module — Round 3 Review: Verification of Rounds 1–2 and Remaining Findings

> **Round 3** review on 2026-10-10 against `main` @ `813796d8` (≈15.5k main lines, 103 test classes).
> Round 1 (36 findings) and round 2 (R1–R17, seven phases) are merged. This round verifies each
> item against the code, then looks again for bugs, efficiency problems and missing best practices.
> Earlier write-ups are in git history: `git log --follow core/docs/core-improvements.md` (round 1 is also at
> `git show a26f3dbd:core/docs/core-improvements.md`; round 2 is the revision before this one).
> Companion: [`core-module-guide.html`](core-module-guide.html), the interactive guide (pipeline,
> package explorer, control model, state machine, filterable improvement board).

**How findings were checked.** Every finding below was confirmed by reading the code on `main`.
Three were also reproduced with throwaway probe tests (since deleted): R18 (event-sink overflow),
R21 (cost of the activity clock) and the effect of R20 (the `module` tag). Findings marked
*not reproduced* rest on code reading alone.

---

## 1. Verdict

The round-2 phases landed and the engine is much closer to the goal: streaming is backpressured,
timeouts are anchored and pause-aware, node outcomes are final and ordered, control is acknowledged
and FIFO, failures can be retried or routed, runs are durable at start/end and restartable, and
admission is governed.

This review found **no data-corrupting bug**, but it did find **one reliability bug that can silently
stop all run tracking (R18)**, **one acceptance criterion from round 2 that is not actually met
(R19)**, and **one defect in the metrics added in the last phase (R20)**. The rest are efficiency,
consistency and hardening items.

| Area | R2 score | R3 score | Notes |
|---|---|---|---|
| Streaming and backpressure | 4.5 | **4.5** | Unchanged. Pause still leaks the downstream prefetch (R24) |
| Status model | 3.5 | **4.5** | Every node ends terminal; ordered events. Event sink can overflow (R18) |
| Time semantics | 1.5 | **3.5** | Anchored and exactly pause-aware. Plugin defaults and per-element timeouts missing (R19) |
| Failure semantics | 2.5 | **4** | Retry, error port, join rule. Retry vs idle clock interplay (R26) |
| Control plane | 2.5 | **4** | Exact step, FIFO, acks, drain. No command id or audit (R25) |
| Restart and recovery | 1.5 | **3.5** | Correct restart for finished runs. In-memory archive, no resume (R25) |
| Resource governance | 2.5 | **4** | Limits, queue, idempotent start, graceful stop. Restart bypasses limits (R23) |
| Simplicity | 4 | **4** | |
| Observability | 2 | **2.5** | Counters and timers exist but the `module` tag is wrong (R20); no tracing |

---

## 2. Verification of rounds 1 and 2

### 2.1 Round 2 (R1–R17)

| ID | Finding | Status | Evidence / what is left |
|---|---|---|---|
| R1 | Timeouts count upstream wait | ⚠️ **Partly fixed** | Clock is anchored at first input (`NodeActivity`). A single step that is silent for longer than the node timeout (default 30 s) **still fails**; no plugin declares a default (→ R19) |
| R2 | Pause counts against timeouts | ✅ Fixed | `NodeActivity.delayUnlessPaused` counts unpaused ticks only (exact to 1/20 of the timeout); also used for the run deadline |
| R3 | Node outcomes never finalised | ✅ Fixed | `CANCELLED`, `TIMED_OUT`, `SKIPPED`, `UPSTREAM_FAILED`, `ABORTED`; `WorkflowState.finalizeTasks` sweeps at run end |
| R4 | Restart-from-node broken | ✅ Fixed | `ExecutionArchive`, `RestartPlan` (descendants + replayed boundary), all messages per node up to a cap, works for finished and failed runs. A truncated output refuses the restart |
| R5 | Step loses credits; pause leaks | ⚠️ **Mostly fixed** | Permit-counter valve on the input gate, exact step. Pause still lets the downstream prefetch (32 per-message elements) through (→ R24) |
| R6 | Events not ordered | ✅ Fixed | One ordered `TrackerEvent` sink with one batched consumer. **But the sink can overflow and die (→ R18)** |
| R7 | "Safe stop" is a hard cancel | ✅ Fixed | `StopMode` `DRAIN` (default, 30 s) → `CANCEL` → `KILL`. The mode is not selectable from REST/MCP yet (→ R28) |
| R8 | Fail-fast only | ✅ Fixed | `engine.retry`, `engine.onError` `FAIL/CONTINUE/ROUTE`, `engine.trigger` `ALL_SUCCESS/ALL_DONE`. `ONE_SUCCESS`/`NONE_FAILED_MIN_ONE_SUCCESS` and a dead-letter terminal plugin are not provided |
| R9 | Control not acked/ordered/idempotent | ⚠️ **Mostly fixed** | Per-execution FIFO (`CommandSequencer`) and an ack that completes when applied. No `commandId` idempotency key and no audit record (→ R25) |
| R10 | Nothing durable | ⚠️ **Partly fixed** | Run start/end in an `ExecutionLogStore` (memory or JSONL); crash recovery marks `ABORTED`. No node/control events, no resume, archive is in memory (→ R22, R25) |
| R11 | No admission control (C15) | ✅ Fixed | `ExecutionScheduler` (limits, bounded queue, `QUEUED`, `SmartLifecycle` drain), shared prepare. No priority or `REPLACE` policy. Restart bypasses it (→ R23) |
| R12 | Validator gaps | ✅ Fixed | Duplicate ids/edges, unknown ports (`error` allowed with `ROUTE`), size limits, Kahn cycle check |
| R13 | Disposables not thread-safe | ✅ Fixed | `CopyOnWriteArrayList` (chosen over `Disposables.composite()` to avoid changing six signatures) |
| R14 | No definition versioning | ✅ Fixed | `DefinitionVersion` hash stored per run and logged; restart uses the archived graph. Not exposed in the API (→ R28) |
| R15 | Starts not idempotent | ✅ Fixed | `Idempotency-Key` with a TTL |
| R16 | No metrics | ⚠️ **Defective** | Micrometer counters/timers exist but are tagged with the wrong `module` (→ R20); no gauges or tracing |
| R17 | Remaining round-1 items | ⚠️ Open | See §2.2 and R29 |

### 2.2 Round 1 leftovers

| ID | Finding | Status |
|---|---|---|
| C15 | Fire-and-forget execution | ✅ Fixed by `ExecutionScheduler` (graceful stop waits for running permits) |
| P5 | Busy-loop sinks | ❌ Open: `busyLooping(100 ms)` remains in the tracker, control bus, valve and `NodeActivity` (→ R18, R21) |
| P6 | O(N·E) compile scan | ❌ Open (low): `WorkflowCompiler` filters all edges per node |
| S7 | Logging volume | ❌ Open (low): 55 `INFO` call sites in `core/src/main` |
| T4 | Step/skip end-to-end test | ❌ Open |
| T6 | Concurrent same-type plugin test | ❌ Open |

---

## 3. New findings (round 3)

Severity: **P0** breaks normal workflows · **P1** wrong results, feature broken, or silent failure ·
**P2** should fix · **P3** clean-up. ✔ means reproduced with a probe test.

### R18 · P1 · The tracker's event sink can overflow and permanently stop all tracking ✔
- **Where:** `DefaultTaskTrackerService.eventSink` is `Sinks.many().multicast().onBackpressureBuffer(4096, false)`
  and receives **every** task, workflow *and log* event through
  `emitNext(…, busyLooping(100 ms))`. `ControlBusService.controlSink` is the same pattern with a
  256-slot buffer.
- **Mechanism:** If producers outrun the single consumer, the buffer fills. `emitNext` then spins
  for up to 100 ms on the producer thread and, on overflow, **terminates the sink with
  `OverflowException`**. The consumer chain ends with `.subscribe()` and no error handler, so the
  error is dropped. After that every `tryEmitNext` returns `FAIL_TERMINATED`.
- **Probe:** a consumer blocked while 20 000 events were emitted → the consumer received
  `OverflowException: Backpressure overflow during Sinks.Many#emitNext`, and the next
  `tryEmitNext` returned `FAIL_TERMINATED`.
- **Impact:** A chatty plugin (for example a process printing thousands of lines per second) can
  kill progress, status and SSE for **every** run until the JVM restarts, with no error visible to
  callers. Log lines (low value) share the queue with state transitions (critical).
- **Fix:**
  - Send log lines through a separate bounded path with `tryEmitNext` and a counted drop; never let
    them compete with state events.
  - Give state events an unbounded queue (`Queues.unbounded()`) or a bounded one that applies
    back-pressure to the *caller*, not the sink.
  - Add an error handler to every long-lived `subscribe()` that logs and resubscribes.
  - Expose a `yukta.tracker.dropped` counter and a queue-depth gauge.

### R19 · P1 · A single long step still hits the 30 s timeout; no plugin declares its own default
- **Where:** `WorkflowCompiler.getNodeTimeout` uses `timeoutSeconds` or `plugin.getDefaultTimeout()`;
  a grep shows **no plugin overrides** `getDefaultTimeout()` (`process-executor` advertises 300 s
  in its own config, `llm-call` 120 s). The idle clock is `touch()`ed on input and output only.
- **Impact:** Round 2's acceptance test "a 60 s process step followed by a terminal succeeds with
  default config" is **not met** for a process that stays silent for more than 30 s. Round 2 only
  stopped counting the *upstream wait*. *Not reproduced.*
- **Fix (in this order):**
  1. Plugins declare realistic defaults: `process-executor` = configured `timeout` + margin;
     `llm-call` = `timeout × (retries + 1)`; `http-request` = its `timeout`.
  2. A per-element processing timeout for per-message nodes, separate from idle.
  3. A `heartbeat()` call on the plugin context that resets the idle clock (long streams, progress).
  4. A node `deadline` (wall clock from first input), off by default.

### R20 · P2 · The `module` tag on node metrics (and in the UI) is always `"default"`
- **Where:** `NodeStreams` and `TerminalNodeAssemblerStrategy` emit task events with
  `DEFAULT_TASK_ID = "default"` as the *module*. `DefaultTaskTrackerService.recordNode` (added in
  round 2, phase 7) tags `yukta.node.executions` and `yukta.node.duration` with that value.
- **Impact:** All nodes land in one time series, so per-plugin latency and failure rates cannot be
  read, which defeats R16. `TaskProgress.module` is equally uninformative in the UI.
- **Fix:** Emit the plugin type (`node.type()`) as the module. Tag metrics with plugin type and
  status only. Do not tag with `nodeId` or `executionId` (unbounded cardinality).

### R21 · P2 · The activity clock costs about 2–3 µs per message per call site ✔
- **Where:** `NodeActivity.touch()` emits into a `replay().latest()` sink with a spin handler, and
  every emission makes `switchMap` cancel and re-create a `Flux.interval` (a scheduler task). It is
  called twice per message per node (input side and output side).
- **Probe:** 200 000 `touch()` calls took ≈ 410–430 ms once warm (≈ 2 µs each), single-threaded.
  Two calls per node per message is ≈ 4 µs; a 20-node chain adds ≈ 80 µs per message of pure
  bookkeeping and a timer churn of 40 scheduler tasks per message.
- **Impact:** Caps throughput at roughly 12 k messages/s for a 20-node DAG, against the §5.6 budget
  of "≤ 2 allocations beyond the plugin's".
- **Fix:** Make `touch()` a single `AtomicLong` write of `System.nanoTime()` and run **one** periodic
  check per node (every `timeout/20`) that compares against it and honours pause. No sink, no
  per-message timer. Keep the exact pause behaviour by counting only unpaused ticks.

### R22 · P2 · The JSONL execution log grows without bound and writes on the wrong threads
- **Where:** `JsonlExecutionLogStore.append` opens, appends and closes the file for every event
  (`Files.writeString`, `createDirectories`). It is trimmed only at startup (`ExecutionRecovery`).
  It is called from `WorkflowOrchestrator.execute` (the subscriber thread, possibly a Netty event
  loop) and from the tracker's batch consumer, which runs on `Schedulers.parallel()`.
- **Impact:**
  - A long-running server grows `executions.jsonl` forever.
  - Blocking file I/O on the event loop and on the shared parallel scheduler delays every
    `NodeActivity` tick and timer in the process.
  - No `fsync`: a power loss can lose the last records (acceptable, but undocumented).
- **Fix:** One writer thread (or `boundedElastic`) with batched appends; compact or rotate when the
  file passes N runs or M MB; document the durability level; keep `replaceAll` atomic as it is.

### R23 · P2 · Restart paths bypass admission control
- **Where:** `RestartCommandProcessor` and `RestartFromNodeCommandProcessor` call
  `orchestrator.execute/restartFromNode(...).subscribe()` and report success at once. Only
  `DefaultControlBusGateway.startWorkflow` calls `ExecutionScheduler.assertCanAccept`.
- **Impact:** With `on-limit=REJECT` (or a full queue) a restart is acknowledged as started, then the
  new run fails with "Execution limit reached". With `QUEUE` it is queued silently.
- **Fix:** Call `assertCanAccept` in both processors before creating the new execution id, so the
  caller gets the 429, as for a start.

### R24 · P2 · Pause still lets a prefetch of elements through
- **Where:** `ExecutionControl.applyPassageGate` is `concatMap(…, 1)`, but the next operator in a
  per-message processor is `channelInput.concatMap(…)` with the default prefetch of 32, so up to 32
  already-passed elements are processed after a pause. Stream-shaped plugins buffer as they like.
  *Not reproduced.*
- **Impact:** "Pause stops pulling" is true only to within the downstream prefetch; side effects of
  those elements happen after the pause.
- **Fix:** `concatMap(fn, 1)` in `ProcessorNodeAssemblerStrategy` for the per-message path; document
  the residual for stream-shaped plugins.

### R25 · P2 · Durability and audit gaps
- **Control audit:** round 2 (R9) promised that every command and result is recorded. Only run
  start/end are logged; commands are not, and there is no `commandId`.
- **Restart after a JVM restart is impossible:** `ExecutionArchive` and the checkpoint store are in
  memory, so recovered runs are history only (`ABORTED`), never restartable.
- **Checkpoints are bounded by count, not size:** `yukta.restart.messages-per-node` (200) ×
  `retained-executions` (10) × nodes × payload size can be very large for big payloads
  (pitfall 19, the unused `ClaimCheckStore`).
- **Fix:** Log `CONTROL_REQUESTED/APPLIED/REJECTED` with an optional `commandId` (deduplicate on it);
  bound checkpoint memory by bytes and spill to the claim-check store above a threshold; decide
  explicitly whether restart-after-reboot is a goal and, if so, persist the archive.

### R26 · P2 · Engine retry and the idle clock interact badly
- **Where:** `NodePolicies.apply` retries a single message with exponential backoff while
  `NodeStreams` measures idle time from the last input or output. A retrying node emits nothing
  during attempts and backoff.
- **Impact:** With a 30 s timeout, `maxAttempts=3` and a plugin that takes 20 s per attempt, the node
  times out during the second attempt even though the policy allows it. `retry` also retries every
  exception, including non-transient ones (a validation error).
- **Fix:** Touch the activity clock before each retry and on each attempt; add a `retryOn` /
  `noRetryOn` class list (default: everything except `IllegalArgumentException`); validate that
  `maxBackoffMs` is below the node timeout or warn.

### R27 · P2 · A prepare in flight can re-populate the cache with a stale definition
- **Where:** `DefaultControlBusGateway.prepareShared` shares one prepare per `(session, workflow)` and
  then `preparedWorkflowCache.put(...)`. `ControlBusService.compileAndCacheWorkflow` and
  `SessionService` invalidate the cache when a definition changes, but not the `preparing` map.
- **Impact:** A start that began preparing the old definition just before it was replaced writes
  the old graph back into the cache for the cache TTL (10 min); later starts run the old definition.
  Narrow race, silent wrong behaviour. *Not reproduced.*
- **Fix:** Key the shared prepare and the cache by the definition's content hash
  (`DefinitionVersion`), or drop the `preparing` entry when invalidating.

### R28 · P3 · Small hardening items
- `StopWorkflowCommandProcessor.drain` starts `Mono.delay(drainTimeout).subscribe(...)` and never
  disposes it; the timer and the `ExecutionControl` it captures live 30 s after the run ended.
- `StartIdempotency.once` scans the whole map (`removeIf`) on every keyed start; use a Caffeine cache
  with `expireAfterWrite`.
- `WorkflowValidator.hasPort` calls `plugin.getOutputPorts(config)` before `validateConfig`; a plugin
  that assumes a valid config could throw instead of returning a clean message. *Not reproduced.*
- The stop mode is not exposed in REST/MCP; the definition version is not in the API responses; join
  rules `ONE_SUCCESS` and `NONE_FAILED_MIN_ONE_SUCCESS` and a dead-letter terminal plugin are not
  provided; `ExecutionScheduler.running()`/`queued()` are not registered as gauges.
- No message `attempt`/`messageId` header, so plugins cannot dedupe retried side effects (pitfall 8).

### R29 · P3 · Open round-1 items and test gaps
- P5 (spin-wait sinks), P6 (O(N·E) compile), S7 (55 `INFO` call sites), T4 (end-to-end step/skip),
  T6 (concurrent same-type plugins).
- 11 coverage exceptions remain in `core/build.gradle.kts`; the most important class, 
  `WorkflowOrchestrator` (`runTracked`, `restartFromNode`), has the lowest branch coverage (0.87).
- There is no test that drives the real orchestrator through the round-2 acceptance scenarios
  (60 s step, 2-minute pause, `step 2`, stop-during-run, kill and recover). A throughput regression
  test (20-node chain, 10-way diamond) is still missing (§5.6 of round 2).

---

## 4. DAG pitfall scorecard (updated)

✅ handled · ⚠️ partly · ❌ not handled.

| # | Pitfall | State | Notes |
|---|---|---|---|
| 1 | Cycles and malformed graphs | ✅ | Cycles, duplicates, ports, size limits |
| 2 | Join after a branch never fires | ⚠️ | `ALL_SUCCESS`/`ALL_DONE`; no `ONE_SUCCESS` |
| 3 | Fan-out to a slow child | ✅ | `publish(bufferSize)` |
| 4 | Late subscriber misses data | ✅ | `NodeBroadcast` connects after all edges subscribe |
| 5 | Timeout semantics | ⚠️ | Anchored and pause-aware; plugin defaults missing (R19) |
| 6 | Long work looks dead | ❌ | No heartbeat API (R19) |
| 7 | Transient failure fails the run | ✅ | `engine.retry` (see R26) |
| 8 | Retries duplicate side effects | ❌ | No `attempt`/`messageId` header (R28) |
| 9 | Fail-fast vs partial results | ✅ | `onError` |
| 10 | Crash loses state | ⚠️ | Start/end durable, no resume (R22, R25) |
| 11 | Re-running part of a graph | ✅ | `RestartPlan` |
| 12 | Definition changes under runs | ✅ | Hash per run (stale prepare race: R27) |
| 13 | Unbounded concurrency | ✅ | `ExecutionScheduler` (restart bypass: R23) |
| 14 | Cancellation reaches the work | ✅ | `DRAIN`/`CANCEL`/`KILL` |
| 15 | Control races | ✅ | FIFO + ack |
| 16 | Observability | ⚠️ | Metrics tag bug (R20), no tracing |
| 17 | Poison message | ✅ | `ROUTE` to the `error` port |
| 18 | Status/event ordering | ⚠️ | Ordered, but the sink can die (R18) |
| 19 | Unbounded memory per run | ⚠️ | Count-bounded checkpoints only (R25) |
| 20 | Starvation and priority | ❌ | No priority |

---

## 5. Best practices to adopt

1. **Never mix critical and best-effort traffic in one queue** (R18). State transitions need
   guaranteed delivery; logs may drop and be counted.
2. **Every long-lived `subscribe()` needs an error handler** that logs and resubscribes. Silent
   `onErrorDropped` is how R18 goes unnoticed.
3. **No blocking I/O on `parallel()` or event-loop threads** (R22). Offload to `boundedElastic` or a
   dedicated writer, and consider BlockHound in tests to enforce it.
4. **Keep per-message overhead off timers and sinks** (R21): a volatile timestamp plus one periodic
   check beats a timer per message.
5. **Low-cardinality metric tags only** (R20): plugin type and status, never ids.
6. **One admission path** (R23): every way of creating a run goes through the scheduler.
7. **Acceptance criteria must be executable** (R29): each phase's criterion becomes an integration
   test, so "60 s step succeeds" cannot silently stay unmet.
8. **Make cache keys content-addressed** (R27) instead of invalidating by name.

---

## 6. Roadmap (remaining work)

| Phase | Items | Outcome | Acceptance test |
|---|---|---|---|
| **8. Make tracking safe** (1–2 days) | R18, R21, R22 | Tracker cannot die; cheap activity clock; non-blocking log | 100 k log lines in 1 s: run statuses still correct and `yukta.tracker.dropped` > 0; 20-node chain ≥ 50 k msg/s |
| **9. Correctness of limits and tags** (1–2 days) | R20, R23, R24, R26, R27 | Right metric tags, restart honours limits, tighter pause, safer retry, no stale prepare | Restart with `REJECT` returns 429; paused node processes ≤ 1 more element; retry of a 20 s step under a 30 s timeout succeeds |
| **10. Plugin-aware time** (2 days) | R19 | Long steps work with default config | A 60 s process step followed by a terminal succeeds with default config |
| **11. Durability and polish** (2–3 days) | R25, R28, R29 | Audit trail, byte-bounded checkpoints, exposed stop mode and version, end-to-end tests | Control commands appear in the log; every round-2 acceptance scenario is an automated test |

---

## 7. Sources (R&D)

- Airflow trigger rules and the branch/join pitfall: [Astronomer — Airflow trigger rules](https://www.astronomer.io/docs/learn/airflow-trigger-rules)
- Durable execution, event history and replay: [Temporal — Event History (Java)](https://docs.temporal.io/encyclopedia/event-history/event-history-java)
- Checkpointing under backpressure: [Flink — From aligned to unaligned checkpoints](https://flink.apache.org/2020/10/15/from-aligned-to-unaligned-checkpoints-part-1-checkpoints-alignment-and-backpressure/)
- Retries, backoff and concurrency limits: [Argo Workflows — retries](https://argo-workflows.readthedocs.io/en/latest/retries/)
- Reactor sink semantics (`Sinks.Many#emitNext`, `EmitFailureHandler`, overflow): [Reactor reference — Sinks](https://projectreactor.io/docs/core/release/reference/coreFeatures/sinks.html)
- Detecting blocking calls on non-blocking threads: [BlockHound](https://github.com/reactor/BlockHound)
