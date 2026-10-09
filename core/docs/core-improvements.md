# Core Module — Review, Improvements & Optimisations

> Scope: `core/src/main` (≈14k lines, 120 classes) reviewed on 2026-10-09 against `main` @ `8d3161b5`.
> Companion: [`core-module-guide.html`](core-module-guide.html) is the interactive guide. It has the
> architecture, the execution lifecycle, and a filterable board of the findings below.

## 1. Verdict

The core is **well-structured but over-built in places and under-wired in others**. The
prepare → compile → assemble → execute pipeline is sound. Records keep data immutable, and the
strategy-per-plugin-category split is clean. Coverage is high: 94 test classes cover 120 main classes.

The weak spots are where the layers meet:

- **The execution status model is stringly typed.** Different classes emit different vocabularies.
  The control layer sends values that the tracker can't parse, and that drops status events.
- **Several control features are exposed but never wired in.** Step mode, runtime skip, workflow-level
  immediate stop, and terminal timeouts are accepted by the API but don't change execution.
- **The hand-rolled broadcast "connector" bypasses Reactor backpressure.** It also does a lot of
  per-message work: stringifying payloads, checkpointing, debug logging, all on every node.
- **Single-call-site fluent builders and dead code inflate the module.** HeartbeatBuilder,
  ReactiveLock and the unused control maps are examples, and they're why the 12 coverage
  exceptions exist in `build.gradle.kts`.

Fixing P0/P1 items is mostly **deleting or re-pointing code**, not adding it.

### Scorecard

| Area | Score | Notes |
|---|---|---|
| Architecture / layering | 4/5 | Clear pipeline. Strategy pattern fits. Some classes depend on the concrete tracker |
| Correctness | 2.5/5 | Status crash, stop→SUCCESS, no-op step/skip, no-op terminal timeout |
| Reactive hygiene | 3/5 | Good use of `Mono.using`, context. Manual subscribe/sink bridging breaks backpressure |
| Performance | 3/5 | Fixed-size "virtual" pool, 2N idle timers per run, per-message payload logging |
| Simplicity | 2.5/5 | 4 builder classes with one caller each, dead controls, ~40% of lines are logging |
| Tests | 4/5 | High coverage, but the tracker is always mocked, so cross-component bugs slip through |

---

## 2. Findings

Severity: **P0** fix now (wrong results or data loss) · **P1** fix next (feature broken or
reliability risk) · **P2** should fix · **P3** clean-up. Effort: S < ½ day, M ≈ 1–2 days, L > 2 days.

### 2.1 Correctness

#### C1 · P0 · Control status strings crash the tracker's batch handler — Effort S
- **Where:** `service/control/processor/*CommandProcessor.java` call
  `emitWorkflowStatusEvent(id, "NODE_PAUSED" | "WORKFLOW_STOPPED" | "PAUSED" | "STEP_MODE_ENABLED" | …)`.
  `DefaultTaskTrackerService.handleWorkflowStatusEvents` then calls `isTerminal(status)`, which runs
  `TaskStatus.valueOf(status)`.
- **What happens:** `valueOf` throws `IllegalArgumentException` for any non-enum value. The batch
  `onErrorResume` swallows it, so **every other event in that 100-event batch is lost**, for all
  executions. The state is left with `status="NODE_PAUSED"` and an `endTime` set. Later,
  `getStatusStream().takeUntil(isWorkflowTerminal)` calls `valueOf` again and errors the SSE stream.
- **Why tests miss it:** processor tests mock `DefaultTaskTrackerService`.
- **Fix:** Lifecycle status and control events are different things. Make the tracker API take
  `TaskStatus`, the enum (see S1). Control processors should stop writing workflow status. Pause and
  step are control state, not lifecycle. If the UI needs them, add a separate `controlState` field on
  `WorkflowProgress`. Stop-gap: make `isTerminal` tolerant (`EnumUtils`-style lookup → false).

#### C2 · P0 · A stopped workflow is reported as `SUCCESS` — Effort S
- **Where:** `WorkflowOrchestrator.execute` has
  `Mono.firstWithSignal(execution, immediateStop, safeStop).then(finishWorkflow("SUCCESS"))`.
- **What happens:** A stop signal wins the race, completes empty, and `SUCCESS` is recorded.
- **Fix:** Add `STOPPED` to `TaskStatus` (terminal). Map the stop branch to it:
  `safeStop.asMono().then(Mono.just(STOPPED))` vs `execution.thenReturn(SUCCESS)` →
  `finishWorkflow(result)`.

#### C3 · P1 · Two owners emit the terminal workflow status — Effort S
- **Where:** `ResourceManagementBuilder.emitStatus("SUCCESS"|"ERROR")` *and*
  `WorkflowOrchestrator` `finishWorkflow("SUCCESS"|"FAILURE")`.
- **What happens:** Clients see `ERROR` then `FAILURE`. `endTime` is overwritten and
  `scheduleCleanup` is scheduled twice.
- **Fix:** Delete `emitStatus` from `ResourceManagementBuilder`. The orchestrator is the only owner.

#### C4 · P1 · Each node error emits `FAILURE` twice — Effort S
- **Where:** `StreamBuilder.applyTaskTrackingTransform` (doOnError → FAILURE) and
  `applyErrorsTransform` (doOnError → FAILURE). Both are enabled by `StreamAssemblyHelper`.
- **Fix:** Delete `withErrorHandling` / `applyErrorsTransform`.

#### C5 · P1 · Runtime "skip node" has no effect — Effort M
- **Where:** `ProcessorNodeAssemblerStrategy` reads `skipFlag.get()` **once at assembly time**. The
  `ExecutionControl` is created a few lines earlier with all flags `false`. The skip branch in
  `ExecutionControl.applyPreProcessingControls` only logs.
- **Fix:** Pick one and make it honest:
  1. Accept a `skipNodes` set at start time and reject `SkipNodeCommand` for running executions
     with a clear error (recommended, simplest), or
  2. Do a per-message bypass, `merged.flatMap(m -> skip.get() ? Flux.just(m) : processOne(m))`.
     This only works for per-message processors, not stream-shaped ones.

#### C6 · P1 · Step mode is a no-op — Effort S
- **Where:** `Enable/DisableStepModeCommandProcessor` flip `ExecutionControl.nodeStepModes`.
  `StepNodeCommandProcessor` emits on `nodeStepSinks`. **No stream reads either.**
  `ReactiveControlValve` already has working `enableStepMode()/step()/disableStepMode()`, but nothing
  calls them.
- **Fix:** The processors should call `control.nodePauseValves().get(node).enableStepMode()` /
  `.step()` / `.disableStepMode()`. Delete `nodeStepModes` and `nodeStepSinks` from
  `ExecutionControl` and `ExecutionControlFactory`.

#### C7 · P1 · Terminal-node timeout never fires — Effort S
- **Where:** `TerminalNodeAssemblerStrategy` has
  `safeInput.flatMap(msg -> Mono.just(msg).timeout(timeout).onErrorMap(TimeoutException.class, e -> e), 1024)`.
- **What happens:** `Mono.just` emits immediately, so the timeout can't trigger, and `onErrorMap` is
  the identity. Terminal nodes have **no timeout**. The `flatMap` only adds overhead.
- **Fix:** Delete the `flatMap`. Apply `completion = completion.timeout(timeout, scheduler)` for an
  overall terminal timeout, or `safeInput.timeout(timeout)` for idle time, matching `StreamBuilder`.

#### C8 · P1 · The broadcast sink bypasses backpressure, so slow consumers cause overflow errors — Effort M
- **Where:** `StreamTopologyDecorator.applyLoggingAndBroadcasting` uses a connector that calls
  `processedStream.subscribe(sink::emitNext…)` (unbounded request) into
  `Sinks.many().multicast().onBackpressureBuffer(bufferSize)`.
- **What happens:** When a child is slower than its parent, the buffer fills. Then `emitNext` hits
  `FAIL_OVERFLOW`, the retry handler only retries `FAIL_NON_SERIALIZED`, and the node stream
  **errors with "Backpressure overflow"**. Upstream is never slowed down. `parkNanos` busy-waits
  under contention.
- **Fix (also a simplification):** Use Reactor's own connectable flux:
  ```java
  ConnectableFlux<Message<?>> hot = processedStream.publish(bufferSize);
  connectors.add(() -> disposables.add(hot.connect()));
  return hot;
  ```
  `publish` requests only what the slowest subscriber can take. This removes `RETRY_HANDLER`,
  `handleEmitFailure`, and the manual sink.

#### C9 · P1 · Plugin instances are shared singletons but get per-node `initialize` and `shutdown` — Effort M (design)
- **Where:** `PluginRegistry` maps `type → single Spring bean`. `WorkflowPreparator` calls
  `plugin.initialize(node.config())` for every node. On any prepare failure it calls
  `plugin.shutdown(config)` on the **shared** instance.
- **Risk:** Two nodes of the same type, or two workflows, share state set by `initialize`. A failed
  prepare in one session can shut down a plugin that another running workflow is using.
- **Fix:** Write the contract down in `plugin-api`: plugins are stateless, and config is passed to
  `start/process/consume` anyway. Remove the shutdown-on-failure fan-out. If a plugin really needs
  per-node state, add a `PluginFactory` and keep the instance in `PreparedWorkflow.pluginCache`.

#### C10 · P2 · A bad edge gives an NPE instead of a validation error — Effort S
- **Where:** `WorkflowPreparator.prepareWorkflow` builds the adjacency lists synchronously
  **before** `validator.validate(def)`. An edge to an unknown node →
  `nodeMap.get(..) == null` → NPE.
- **Fix:** Move the map building into the `Mono.fromCallable` that runs after `validate`.

#### C11 · P2 · `restartFromNode` with an unknown node silently restarts from the beginning — Effort S
- **Where:** `nodeToIndex.getOrDefault(restartNodeId, 0)`. The restart path also skips
  `immediateStopSink` and `contextWrite`, unlike `execute`.
- **Fix:** Error on an unknown node. Share one lifecycle helper with `execute` (S2).

#### C12 · P2 · Duplicate plugin types silently overwrite each other — Effort S
- **Where:** `PluginRegistry` constructor `plugins.put(type, plugin)`.
- **Fix:** Use `putIfAbsent` and throw on a collision, so startup fails fast.

#### C13 · P2 · Tracker memory grows without bound per session — Effort S
- **Where:** `cleanupExecution` removes `executionIndex` and the sinks but **not**
  `sessionStates[session][exec]`. Long-lived sessions keep every `WorkflowState`. Reads also
  disagree after cleanup: `getProgress(session, exec)` still works, but
  `getProgressByExecutionId(exec)` returns null.
- **Fix:** Remove from `sessionStates` in `cleanupExecution`, or keep a capped history (Caffeine
  `maximumSize`) if the history is wanted.

#### C14 · P2 · `WorkflowState.status/endTime` aren't safely published across threads — Effort S
- They're written on `Schedulers.parallel()` and read on request threads with no `volatile`.
- **Fix:** Mark them `volatile`, or replace the state in the map atomically with `compute`.

#### C15 · P2 · Fire-and-forget execution can't shut down gracefully — Effort M
- **Where:** `DefaultControlBusGateway.prepareAndExecute` calls
  `orchestrator.execute(...).subscribe()` and drops the `Disposable`. `execute()` registers
  `ExecutionControl` eagerly, before anyone subscribes.
- **Fix:** Wrap the `execute` body in `Mono.defer`. Keep the `Disposable` on the registry entry and
  dispose the in-flight runs in `@PreDestroy`.

#### C16 · P2 · Session config writes aren't atomic — Effort S
- **Where:** `FileSessionConfigStore.saveSessionConfig` writes with `TRUNCATE_EXISTING`. A crash
  mid-write leaves a corrupt or empty JSON file.
- **Fix:** Write `<id>.json.tmp`, then `Files.move(tmp, target, ATOMIC_MOVE, REPLACE_EXISTING)`.

#### C17 · P3 · Stopping twice throws — Effort S
- `StopWorkflow/StopNode` processors call `emitEmpty(FAIL_FAST)`, which throws `EmissionException`
  if the sink has already completed.
- **Fix:** Use `tryEmitEmpty()` and ignore `FAIL_TERMINATED`.

#### C18 · P2 · The `VariableResolver` secret blacklist blocks the wrong keys — Effort S
- **Where:** `util/VariableResolver.SECRET_BLACKLIST = .*(PASSWORD|SECRET|KEY).*` is checked
  against **every** expression key: payload, metadata, env and sys.
- **Impact:**
  - `${payload.keyword}` and `${metadata.monkey}` throw `SecurityException`.
  - `${env.GITHUB_TOKEN}` and `${env.DATABASE_URL}` are readable.
  - Combined with `NoOpSecretProvider` (which returns empty), there's no supported way to pass an
    API key except plaintext in workflow config. See plugins G5 and G6.
- **Fix:** Apply it only to `env.` and `sys.` with an allow-list prefix (for example `YUKTA_`).
  Ship an `EnvSecretProvider` for `decrypted:` keys.

### 2.2 Performance and optimisation

#### P1 · High · The "virtual thread scheduler" is capped at nCPU threads — Effort S
- **Where:** `AppConfiguration.virtualThreadScheduler()` uses
  `Executors.newScheduledThreadPool(availableProcessors(), Thread.ofVirtual().factory())`.
- **Impact:** A *fixed* pool of N virtual threads. JVM-wide, at most N blocking plugin calls
  (`isBlocking()`) can run at once, so virtual threads give no benefit here. On an 8-core box, the
  9th concurrent shell or HTTP step queues.
- **Fix (built in, no code):** use `Schedulers.boundedElastic()` with
  `-Dreactor.schedulers.defaultBoundedElasticOnVirtualThreads=true` (Reactor ≥ 3.6). Or use
  `Schedulers.fromExecutorService(Executors.newVirtualThreadPerTaskExecutor())` for blocking work
  and `Schedulers.parallel()` for timeouts.

#### P2 · High · HeartbeatBuilder starts 2 × N idle timers per execution — Effort S
- **Where:** `WorkflowCompiler.executeTemplate` → `HeartbeatBuilder.build()`. That's one heartbeat
  and one stats `Flux.interval` per node, and each only does `log.debug` → `Mono.empty()`.
- **Impact:** With 20 nodes and 50 concurrent runs, that's 2,000 scheduled tasks doing nothing.
- **Fix:** Delete `HeartbeatBuilder`, its test, and the `heartbeatInterval` wiring. Re-add it if real
  heartbeats are ever emitted (YAGNI).

#### P3 · High · Every message's payload is stringified and logged at every node — Effort S
- **Where:** `StreamTopologyDecorator.getMessageFlux` runs, per message per node,
  `String.valueOf(payload)` → `splitLogLine` → `tracker.emitLogEvent(...)` into a global sink with a
  100 ms busy-loop retry.
- **Impact:** Large payloads (file contents, JSON) are serialised once per node. Log volume is
  O(messages × nodes). A single global log sink is shared across executions.
- **Fix:** Make payload echo opt-in (`node.config.logPayload: true`) or truncate to about 2 KB.
  Plugins already have `PluginLogger` for real logs.

#### P4 · Medium · A checkpoint is written for every message — Effort S
- **Where:** `logStream.flatMap(msg -> checkpointStore.save(...).thenReturn(msg))`.
- **Impact:** The in-memory store overwrites the entry, so only the last message matters, but every
  message still pays for it. With a persistent store, `flatMap` can also **reorder** messages.
- **Fix:** Use `concatMap` (keeps order). Better: only checkpoint nodes that have children, and
  only the last message (`doOnNext(last::set)` + save on complete).

#### P5 · Medium · Global sinks busy-loop under contention — Effort M (measure first)
- The tracker has three global multicast sinks and the control bus has one, all with
  `busyLooping(100ms)`. Many concurrent producers → `FAIL_NON_SERIALIZED` spin.
- **Fix:** Measure with JFR first. If it's hot, partition by `executionId.hashCode() % k`, or have
  producers call `tryEmitNext` and drop or count on failure. These are telemetry events, not data.

#### P6 · Low · Per-prepare overhead in the compiler — Effort S
- `createNodeAssembler` filters the whole edge list for each node, O(N·E). The topological sort's
  error path uses `result.contains`, O(N²). Logging arguments like
  `def.nodes().stream().map(...).toList()` are built even when debug is off.
- **Fix:** Group incoming edges once (`Collectors.groupingBy(WorkflowEdge::target)`). Use a `Set`
  in the error path.

#### P7 · Low · Per-execution control allocations — Effort S
- `ExecutionControlFactory` allocates six maps per node, including the step maps nobody reads (C6)
  and a workflow `immediateStopSink` that nothing ever emits.
- **Fix:** Deleting the dead controls (S5) removes these.

### 2.3 Simplification and maintainability

| ID | Finding | Fix | Effort |
|---|---|---|---|
| S1 | Status is a free-form `String` in the `TaskTrackerService` API, with about 15 distinct literals (5 lifecycle values, 10 control values) spread across the tracker, strategies and processors | Use `TaskStatus` in signatures and add `STOPPED`. The compiler then catches C1, C2 and C3 | M |
| S2 | `execute()` and `restartFromNode()` duplicate about 60 lines of lifecycle code (register → start → firstWithSignal → finish → cleanup) | Extract `private Mono<Void> runTracked(ExecutionControl, List<String> nodeIds, Mono<Void> body)` | S |
| S3 | Four fluent builders each have one caller that always sets every flag: `StreamBuilder`, `ResourceManagementBuilder`, `HeartbeatBuilder`, `ExecutionContextBuilder` | Replace them with plain static methods. About 700 lines of main and test code go away | M |
| S4 | 11 control processors inject the concrete `DefaultTaskTrackerService` | Inject the `TaskTrackerService` interface | S |
| S5 | Dead code: `util/ReactiveLock`, `ReactiveControlValve.asTransform`, `HeartbeatBuilder`, `nodeStepModes/nodeStepSinks`, workflow `immediateStopSink`, `StreamBuilder.withErrorHandling`, the compiler's no-op assembler fallback (the preparator already rejects unknown plugins), and the log-only skip branch | Delete | S |
| S6 | Reactor context keys (`"sessionId"`, `"workflowId"`, …) are redefined in four classes | Use one source: `ExecutionContextBuilder.CTX_*` | S |
| S7 | About 40% of lines are log statements, many at debug per message per operator | Keep lifecycle logs and put the IDs in the context or MDC once | M |
| S8 | `build.gradle.kts` lowers coverage thresholds for 12 classes | After S3 and S5, try removing the exceptions. They mostly cover dead branches | S |
| S9 | `ExecutionContextBuilder` and `AssemblyContext` copy the payload into `ConcurrentHashMap` or `Map.copyOf`. `Map.copyOf` throws an NPE if a JSON payload has `null` values | Use `Collections.unmodifiableMap(new HashMap<>(p))` once, at the boundary | S |
| S10 | `core/CLAUDE.md` lists `MCP Tools: …/mcp/`, but there's no `mcp` package in core (it lives in the `mcp` module) | **Done** in this change: path removed, guide linked | S |

### 2.4 Test gaps

| ID | Missing test | Catches |
|---|---|---|
| T1 | Real `DefaultTaskTrackerService` + `PauseNodeCommandProcessor`, asserting that the status stream still terminates | C1 |
| T2 | Execute, then stop, asserting the final status is `STOPPED` | C2, C3 |
| T3 | A trigger emitting 10k messages into a slow processor with `bufferSize: 16`, asserting no overflow error and that all messages arrive | C8 |
| T4 | End-to-end tests for step mode and skip | C5, C6 |
| T5 | A terminal plugin that never completes, asserting a timeout | C7 |
| T6 | Two nodes of the same plugin type with different config, run concurrently | C9 |

---

## 3. Suggested roadmap

> **Progress:** Phase 1 done on `fix/core-status-correctness`: C1 stop-gap, C2, C3, C4, C7 (tests T1, T2, T5). 
> Phase 2 done on `refactor/core-typed-status`: S1, S4 and the full C1 fix (`ControlState` / `WorkflowProgress.controlState`; the C1 stop-gap is removed).
>
> Phase 3 done on `refactor/core-dead-code`: S5, P2, P7, C6, C17, S6, and S8 in part. Only the `TerminalNodeAssemblerStrategy` coverage exception could be dropped; the others still fail at 100%.

| Phase | Items | Outcome |
|---|---|---|
| **1. Stop the bleeding** (≈1 day) | C1 stop-gap, C2, C3, C4, C7, T1, T2, T5 | Statuses are correct and SSE streams terminate |
| **2. Type the status model** (≈1–2 days) | S1, S4, then the full C1 fix | Status bugs become compile errors |
| **3. Delete before optimising** (≈1 day) | S5, P2, S6, C6 (wire the valve), C17 | Smaller surface. Coverage exceptions can shrink |
| **4. Throughput** (≈2 days) | P1, C8 (`publish()`), P3, P4, T3 | Real backpressure and real virtual threads. Less work per message |
| **5. Robustness** (≈2 days) | C5, C9 contract, C10–C16, C18, S2, S3 | Predictable restart, skip, shutdown and storage |

Measure before and after phase 4. A JMH benchmark or a simple `StepVerifier` throughput test is
enough: a 20-node linear DAG, 10k messages, with throughput and allocation rate recorded.
