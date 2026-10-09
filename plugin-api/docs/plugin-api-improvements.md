# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited

# plugin-api — Review, Improvements & Optimisations

> Scope: `plugin-api/src/main` (22 files, ≈1.05k lines) reviewed on 2026-10-09 against `main` @ `8d3161b5`.
> Companion: [`plugin-api-module-guide.html`](plugin-api-module-guide.html) is the interactive guide.
> Related: [`core-improvements.md`](../../core/docs/core-improvements.md).

## 1. Verdict

`plugin-api` is the contract every plugin, `core`, `web` and `mcp` compile against. The three
category interfaces are small and reactive-first, and that's good: a trigger returns `Flux`, a
processor maps `Flux → Flux`, a terminal maps `Flux → Mono<Void>`. The problems are around the edges:

- **Security:** `SpelUtils` evaluates workflow-supplied expressions with a full
  `StandardEvaluationContext`. Anyone who can submit a workflow can run arbitrary Java on the
  server. I confirmed this locally: `T(java.lang.System).getProperty('java.version')` evaluates.
  The server has no authentication.
- **The contract and its docs have drifted from `core`.** Javadoc promises pause "via
  backpressure", skip, and step semantics that `core` doesn't implement. It also says built-in
  processors have priority 0, and that `process()` emits exactly one directive. Neither is true of
  the built-ins.
- **Unused surface:** a lifecycle hook nobody calls (`prepare`), two stores, an exception, and
  stale coverage exceptions for classes that no longer exist.
- **Dependency weight:** the SPI exports the whole WebFlux starter to every plugin.

### Scorecard

| Area | Score | Notes |
|---|---|---|
| Contract design | 3.5/5 | Clean reactive category interfaces. `Plugin` mixes runtime, validation, UI and optimiser concerns (16 methods) |
| Security | 1/5 | SpEL remote code execution via workflow config |
| Docs vs reality | 2/5 | Several Javadoc promises don't match `core` |
| Simplicity | 3/5 | 4 unused types/hooks, a stringly `commandType` on records |
| Tests | 2/5 | 4 test classes. `SpelUtils` and the control types have a 0% coverage threshold |

---

## 2. Findings

Severity: **P0** fix now · **P1** fix next · **P2** should fix · **P3** clean-up. Effort: S < ½ day, M ≈ 1–2 days, L > 2 days.

### 2.1 Security and correctness

#### A1 · P0 · SpEL expressions from workflow config can execute arbitrary code — Effort S
- **Where:** `util/SpelUtils.evaluate` and `evaluateSync` build a `StandardEvaluationContext`.
  The expressions come straight from node config in `FilterProcessor`, `BranchProcessor`,
  `MapperProcessor`, `RecipientListProcessor`, `LoopStreamProcessor`, `SplitterProcessor` and
  `LlmCallPlugin`.
- **Exploit:** a filter node with
  `condition: "T(java.lang.Runtime).getRuntime().exec('…')"` submitted over REST or MCP. No auth
  is configured in `web`/`boot`.
- **Verified:** `StandardEvaluationContext` resolved `T(java.lang.System)`.
  `SimpleEvaluationContext` rejected it with `SpelEvaluationException`.
- **Fix:**
  ```java
  EvaluationContext ctx = SimpleEvaluationContext
      .forPropertyAccessors(new MapAccessor(), DataBindingPropertyAccessor.forReadOnlyAccess())
      .withRootObject(root)
      .withInstanceMethods()   // only if expressions need e.g. payload.startsWith('x')
      .build();
  variables.forEach(ctx::setVariable);
  ```
  Then add a test asserting that `T(...)`, `new java.io.File(...)` and bean references are
  rejected.

#### A2 · P1 · The SpEL parse cache is unbounded — Effort S
- **Where:** `SpelUtils.CACHE` is a static `ConcurrentHashMap<String, Expression>` keyed by
  expression text.
- **Impact:** Every distinct expression ever submitted stays in memory for the life of the JVM. A
  client generating unique expressions grows the heap without limit.
- **Fix:** Bound it. A synchronized `LinkedHashMap` with `removeEldestEntry` capped at about 1,000
  entries works and needs no new dependency.

#### A3 · P2 · The `ThreadLocal` context in `evaluateSync` is pointless on virtual threads and can leak — Effort S
- **Where:** `CONTEXT_HOLDER` is a `ThreadLocal<StandardEvaluationContext>`. `setRootObject(root)`
  is never cleared.
- **Impact:** On virtual threads (blocking plugins) each thread is new, so nothing is reused. On
  pooled platform threads, the last message's root object stays reachable until the next call.
  Variables are reset to `null`, not removed.
- **Fix:** Build the context per call. `SimpleEvaluationContext` is cheap, and the parse cache is
  where the real savings are. This also lets A1's fix replace both code paths with one method.

#### A4 · P2 · `evaluate()` hops threads for a microsecond operation — Effort S
- **Where:** `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())`.
- **Impact:** Every async evaluation (`LoopStreamProcessor`) costs a thread hop and a task
  allocation for work that is CPU-bound and runs in microseconds.
- **Fix:** Drop `subscribeOn`.

#### A5 · P1 · The control contract's Javadoc doesn't match core — Effort S
| Javadoc says | Core does |
|---|---|
| `ControlSignalProcessor.getPriority`: "built-in processors use 0, so any custom processor with a positive value takes precedence" | Built-ins return 10, 15 or 20, and the dispatcher takes `max`. A custom processor with priority 1–9 **loses** |
| `process()`: "a Mono emitting exactly one `WorkflowDirective`" | Every built-in returns `Mono.fromRunnable(...)` (empty) and does its side effects directly. The `WorkflowDirective` pipeline is only used by custom processors |
| `PauseWorkflowCommand`: "no new elements are pulled from input streams" (backpressure) | Core gates with `filterWhen` **after** the plugin has processed the element |
| `SkipNodeCommand`: "passes messages through" | No effect at runtime (core C5) |
| `EnableStepModeCommand` / `StepNodeCommand` | No effect (core C6) |
| `WorkflowDirective` Javadoc links `com.infenia.yukta.plugin.message.control.ControlCommand` | That type doesn't exist |
- **Fix:** Correct the docs now, then fix the behaviour in core. Make the built-ins priority 0 as
  documented, or document the real numbers.

#### A6 · P1 · Plugin instance lifecycle is undocumented — Effort S (docs) / M (factory)
- **Where:** `Plugin.initialize(config)`, `prepare(config)` and `shutdown(config)` take a
  per-node config, but `PluginRegistry` holds **one singleton bean per type** (core C9).
- **Impact:** Authors will reasonably store config in fields during `initialize`. That state is then
  shared by every node and workflow using the plugin.
- **Fix:** State the rule in the `Plugin` Javadoc: *instances are shared and must be stateless;
  read config from the arguments of `start/process/consume`*. If per-node state is needed later,
  add `PluginFactory#create(config)`.

#### A7 · P2 · `prepare()` is never called — Effort S
- `Plugin.prepare(config)` ("pre-parsing expressions") has no caller anywhere in the repo. Plugins
  pre-parse inside `validateConfig` or `initialize` instead.
- **Fix:** Delete it, or call it from `WorkflowPreparator`. Deleting is simpler.

#### A8 · P2 · Inconsistent exception hierarchy — Effort S
- `FilterEvaluationException` extends `WorkflowExecutionException`, but `JoinTimeoutException` and
  `NoMatchingBranchException` extend `RuntimeException`. Catching `WorkflowExecutionException`
  therefore misses branch failures.
- **Fix:** Have all of them extend `WorkflowExecutionException`. `JoinTimeoutException` is unused,
  so delete it.

#### A9 · P3 · `WorkflowContext` wraps instead of copying — Effort S
- `Collections.unmodifiableList(list)` is a view, so the caller can still mutate it. It also throws
  an NPE on null. The empty compact constructor on `WorkflowEdge` is noise.
- **Fix:** Use `List.copyOf(...)` in a compact constructor. Delete the empty constructor.

### 2.2 Simplification and dependencies

| ID | Finding | Fix | Effort |
|---|---|---|---|
| A10 | `api(libs.spring.boot.starter.webflux)` exports Netty, the WebFlux server, Jackson and more to every plugin, when the SPI only needs Reactor and SpEL | `api(reactor-core)`, `implementation(spring-expression)`, `api(project(":messaging"))`. Plugins that need WebClient declare it themselves | S |
| A11 | Unused types: `ClaimCheckStore`, `IdempotencyStore`, `JoinTimeoutException` have no implementation or caller | Delete. Re-add when a feature needs them | S |
| A12 | `ExecutionControlCommand` is an open interface. Each record repeats a `commandType()` string | Make it `sealed`, so the dispatcher and processors can switch exhaustively. Derive the type name, or drop it if it's only used for logging | S |
| A13 | `Plugin` has 16 methods across four concerns: runtime, validation, UI metadata, optimiser hints | Optional: move descriptive metadata (`description`, `usagePattern`, `uiDesign`, `outputPorts`) into a `PluginDescriptor` record returned by one method | M |
| A14 | `UiDesign.html` is raw HTML that the UI renders. The UI does sanitise it with DOMPurify (`renderNodeHtml`), so this is defence in depth only | Document it as plugin-author content that gets sanitised. Prefer a structured `(colour, icon, label)` descriptor (plugins G22, ui U1) | S |
| A15 | `PluginMetricsReporter.incrementFilterCount` is a filter-specific method in a generic SPI | Generalise to `increment(nodeId, metric, tag)`, or move it into the filter plugin | S |

### 2.3 Build and tests

| ID | Finding | Fix | Effort |
|---|---|---|---|
| A16 | `coverageConfig` lists `gateway.AbstractMessagingGateway` and `core.WorkflowPlugin`, which no longer exist | Delete the stale entries | S |
| A17 | Security-sensitive `SpelUtils` and all `control.*` types have a 0% coverage threshold, and `pmdTest` is disabled | Add `SpelUtilsTest` (sandbox, cache bound, variables) and remove those exceptions | S |
| A18 | No compatibility test of the contract, so a default-method change can silently alter every plugin | One test pins the defaults: timeout 30 s, buffer 1024, port `default`, `isBlocking=false` | S |

---

## 3. Roadmap

| Phase | Items | Outcome |
|---|---|---|
| **1. Close the RCE** (½ day) | A1, A17 | Expressions are sandboxed, with a regression test |
| **2. Make the contract honest** (½ day) | A5, A6, A7, A8 | Javadoc matches behaviour. Lifecycle rules are explicit |
| **3. Trim** (½ day) | A2, A3, A4, A10, A11, A16 | Smaller dependency footprint, bounded memory, less code |
| **4. Evolve** (optional) | A12, A13, A15, A9, A14, A18 | Sealed commands and a descriptor record |
