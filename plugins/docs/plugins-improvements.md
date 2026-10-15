# plugins — Review, Improvements & Optimisations

> Scope: all 9 plugin modules under `plugins/` (≈6.3k main lines) reviewed on 2026-10-09 against `main` @ `8d3161b5`.
> **`http-request`, `llm-call` and `session-memory` were uncommitted work in progress at review
> time.** Their findings are marked *(WIP)*.
> Companion: [`plugins-module-guide.html`](plugins-module-guide.html) is the interactive guide, including a plugin catalogue.

## 0. Status on 2026-10-15

Sections 1 to 3 are the original review of 2026-10-09 and are kept as written. This section says
what has changed since, checked against `main` after PRs #373 (security hardening) and #374 (core
risks). The interactive guide's *Risks and improvement ideas* board has the current list with
impact and effort; this table maps the G numbers onto it.

"Checked" means the code was read on 2026-10-15. Rows marked *not re-checked* come from the
original review and may have changed.

| ID | Status | Evidence |
|---|---|---|
| G1 | Mostly closed | GraalJS class lookup is limited to 12 value classes and `java.time.*`, `Class` objects are denied, scripts stop after 50 M statements or 10 s (`GraalJsScriptEngine`). Open: no memory limit; `HostAccess.ALL` on the objects it is given. Checked |
| G2 | Mostly closed | `SpelUtils` calls only methods on the `SafeMethodResolver` allow-list. Open: no evaluation time budget (see plugin-api guide). Checked |
| G3 | Partly closed | Nothing runs unless `yukta.process.allowed-commands` lists it (or `allow-any=true`); `useShell` is refused while a list is set. With `allow-any` the Windows quoting problem remains. Checked |
| G4 | Open | `useShell` still escapes every metacharacter. Checked |
| G5 | Partly closed | `EnvSecretProvider` exists (`decrypted:<key>` reads `YUKTA_SECRET_<KEY>`). Open: nothing redacts secrets in the read APIs, and `SQL_QUERY` takes `password` literally. Checked |
| G6 | Closed | `${env.*}` and `${sys.*}` read only `YUKTA_VAR_*` / `yukta.var.*`, a short harmless list and names in `yukta.variables.allowed`; secret-looking names stay blocked. Checked |
| G7 | Open | No outbound host allow-list for `HTTP_REQUEST` or `MCP_TOOL_CALL`. MCP now has tool-level controls (allowed tools, approvals, argument limits). Checked |
| G8 | Open | Default concurrency is still 256 for process, HTTP, MCP, session and file/SQL nodes. LLM calls can be spaced per provider (`yukta.llm.rate-limit.<provider>`). Checked |
| G9 | Open | No descendant handling on timeout. Checked |
| G10 | Not re-checked | `ProcessExecutorGateway` changed in #373 (lines stream live; `maxOutputLines` / `maxOutputBytes` bound the kept output). |
| G11 | Open | Mapper caches, `LlmGateway` model clients and the MCP session map are still plain maps. Checked |
| G12 | Not re-checked | |
| G13 | Partly checked | `ESCALATE` still throws a plain `RuntimeException`. RETRY vs SKIP not re-checked |
| G14 | Open | The loop still calls the target from the registry without `validateConfig`. Checked |
| G15, G16 | Not re-checked | |
| G17 | Partly closed | History can be kept in files or PostgreSQL (`yukta.session-memory.ttl` expires database rows). The in-memory cache still never evicts. Checked |
| G18 | Open, slightly worse | 11 plugin modules depend on `:core`; 26 imports of `VariableResolver` (was 24). New shared helpers went into `plugin-api`. Checked |
| G19 | Open | `internal-core` still has `pmdMain`, `pmdTest`, `checkstyleTest` off; `constant-source` has PMD off. Checked |
| G20, G21, G22, G24 | Not re-checked | |
| G23 | Open | `ConsoleTerminalPlugin` still logs the payload at INFO. Checked |
| G25 | Open | `CONTENT-FILTER` is still the only kebab-case id. Checked |

New since the original review, with their ids in the guide:

- `agentGuardsOptIn`: MCP tool lists, approvals, budgets and prompt filters all default to off.
- `decisionUnbudgeted`: LLM-backed decisions and the model control plane bypass the run budget.
- `sessionStoreBlocks`: `SESSION_MEMORY` calls the blocking history store inside a map operation and is not marked blocking.
- `budgetLedger` and `signalWaitsShared` (in the plugin-api guide): the new shared ledger and approval waits are per JVM, in memory, and can overshoot.

Suggested order for the next steps (impact over effort, cheap and high impact first):

1. `sessionStoreBlocks`: one line (`isBlocking() = true`) plus moving the store call out of the map operation.
2. G7 host allow-list (`ssrf`), together with `agentGuardsOptIn` (warn when an MCP tool name comes from the message and no list is set).
3. G5 redaction in the read APIs, and resolving `decrypted:` in `SQL_QUERY`.
4. G8 and G9: a `maxConcurrency` setting and descendant kill on timeout.
5. `decisionUnbudgeted` and the plugin-api `budgetLedger`: count every model call once, in the gateway.
6. G18: move `VariableResolver` and `MapUtils` out of core.

## 1. Verdict

The built-in plugins cover a useful set:
- triggers: API, one-shot, constants;
- routing: branch, recipient list, splitter, filter;
- transformation: mapper, content filter;
- loops, process execution, HTTP, LLM calls and chat memory.

The newer plugins (process-executor, http-request, llm-call) are carefully written: typed config
records, explicit failure modes, port routing, output caps, and good usage docs.

The plugin layer is also where user-supplied workflow config becomes executed code, and the
guard-rails are missing:

- **Remote code execution path 1:** SpEL (plugin-api A1) in Filter, Branch, Mapper, RecipientList,
  Loop, Splitter and LlmCall.
- **Remote code execution path 2:** `MapperProcessor` SCRIPT mode runs workflow-supplied
  JavaScript in GraalJS with `allowHostAccess(ALL)` and `allowHostClassLookup(s -> true)`. That
  means full Java access through `Java.type(...)`, with no time limit.
- **process-executor:**
  - Up to 256 processes can run at once per node.
  - Grandchild processes survive a timeout.
  - The Windows quoting doesn't protect against `cmd.exe` metacharacters.
  - `useShell` escapes every metacharacter, so pipes and redirects never work.
- **There is no way to pass a secret.** `NoOpSecretProvider` returns empty, and
  `${env.*_KEY}` is blocked. So API keys are written in plaintext in workflow definitions, which
  the read APIs return.
- **internal-core turns its own quality gates off:** PMD main and test, Checkstyle test, and JaCoCo
  verification. It also lists coverage exceptions for five classes that don't exist.
- **Plugins depend on `:core`** (`VariableResolver`, `MapUtils`, `PluginRegistry`,
  `DefaultTaskTrackerService`). That inverts the intended layering, where plugins depend only on
  `plugin-api`.

### Scorecard

| Area | Score | Notes |
|---|---|---|
| Feature coverage | 4/5 | Good EIP set plus process, HTTP and LLM |
| Security | 1/5 | Two code-execution paths, server-side request forgery, plaintext secrets, weak shell quoting |
| Resource safety | 2/5 | Unbounded process concurrency, orphaned processes, unbounded caches, no script time limit |
| Layering | 2/5 | Plugins compile against core internals |
| Quality gates | 2/5 | internal-core disables PMD and JaCoCo. MapperProcessor has a 0% coverage threshold |
| Docs | 4/5 | Excellent `getUsagePattern()` text |

---

## 2. Findings

Severity: **P0** fix now · **P1** fix next · **P2** should fix · **P3** clean-up. Effort: S < ½ day, M ≈ 1–2 days, L > 2 days.

### 2.1 Security

#### G1 · P0 · The mapper's SCRIPT mode gives workflow JavaScript full Java access — Effort S
- **Where:** `internal-core/.../transformer/MapperProcessor.executeScript` uses
  `Context.newBuilder("js").allowHostAccess(HostAccess.ALL).allowHostClassLookup(s -> true)`.
- **Exploit:** `mapping: "Java.type('java.lang.Runtime').getRuntime().exec('…')"` in any submitted
  workflow. There's no auth (web W1).
- **Also:** There's no CPU or time limit, so `while(true){}` pins a thread forever.
- **Fix:**
  ```java
  Context.newBuilder("js").engine(JS_ENGINE)
      .allowHostAccess(HostAccess.NONE)          // pass data as ProxyObject/ProxyArray instead
      .allowHostClassLookup(c -> false)
      .allowIO(IOAccess.NONE).allowCreateThread(false).allowNativeAccess(false)
      .resourceLimits(ResourceLimits.newBuilder().statementLimit(1_000_000, null).build())
      .build();
  ```
  Convert `payload`/`metadata` to `ProxyObject.fromMap(...)`, not host objects. Add a
  `@Test` that asserts `Java.type` throws.

#### G2 · P0 · SpEL in plugin config (plugin-api A1) — Effort S (fixed in plugin-api)
- **Affected plugins:** Filter, Branch, Mapper (PROJECTION), RecipientList, LoopStream, Splitter,
  LlmCall.
- **Fix:** In `SpelUtils`, see plugin-api A1. Then add one test per plugin with a `T(...)`
  expression.

#### G3 · P1 · process-executor quoting on Windows is ineffective — Effort S
- **Where:** `ProcessExecutorGateway.escapeShellArg` wraps unsafe args in **single quotes** for
  both `/bin/sh` and `cmd.exe`. `cmd.exe` doesn't treat single quotes as quoting, so `&`, `|` and
  `^` in an argument (for example from `${payload.x}`) still run.
- **Fix:** On Windows, refuse `useShell` when any argument came from interpolation, or apply
  `cmd`-specific `^` escaping. Simplest: don't support `useShell` on Windows.

#### G4 · P1 · `useShell` can't use any shell feature — Effort S (design decision)
- **Where:** `wrapInShell` escapes every metacharacter. The test
  `ProcessExecutorGatewayTest:414` asserts that `"safe;string|with:pipes"` is echoed literally.
  So `useShell: true` only adds PATH and builtin lookup. Pipes, `&&` and redirects never work.
  Meanwhile the "safe" regex allows `( )` and spaces unquoted, which changes word splitting.
- **Fix:** Pick one model and document it:
  - **(a)** `command` is a single trusted shell string taken verbatim from config, and
    interpolation is forbidden when `useShell` is set; or
  - **(b)** remove `useShell` and always exec argv directly. That is safer, and `["sh", "-c", "…"]`
    is still available explicitly.

#### G5 · P1 · There's no way to provide secrets, so API keys sit in plaintext in workflow config — Effort S
- **Where:** core `NoOpSecretProvider.getSecret` returns `Mono.empty()`. `VariableResolver`
  blocks `${env.*KEY*|*SECRET*|*PASSWORD*}`. The llm-call sample
  (`bruno/.../Apply Chat Agent Session.bru`) puts `apiKey` directly in the workflow.
- **Impact:** Keys are stored in session JSON files and returned by
  `GET /api/sessions/{id}/workflows` and MCP `get_workflow_details`.
- **Fix:** Add a small `EnvSecretProvider` (`decrypted:OPENAI_API_KEY` → `System.getenv`) as the
  default. Redact secret-like config keys in the read APIs.

#### G6 · P2 · The `VariableResolver` blacklist blocks the wrong things — Effort S (core)
- **Where:** core `VariableResolver.SECRET_BLACKLIST = .*(PASSWORD|SECRET|KEY).*`, checked against
  the whole expression key.
- **Impact:**
  - False positives: `${payload.keyword}`, `${metadata.monkey}` and `${payload.apiKeyHint}` throw
    `SecurityException`.
  - False negatives: `${env.GITHUB_TOKEN}`, `${env.DATABASE_URL}` and `${sys.javax.net.ssl.trustStorePassword}`
    (blocked) versus `${sys.user.home}` (allowed).
- **Fix:** Apply the rule only to `env.` and `sys.`, and use an **allow-list** of readable
  environment variables (for example a `YUKTA_` prefix) instead of a deny-list.

#### G7 · P2 · http-request can reach internal addresses (server-side request forgery) *(WIP)* — Effort S
- **Where:** `HttpRequestGateway.execute` sends to any `spec.url()`, which can come from
  `${payload.url}`.
- **Impact:** It can reach cloud metadata (`169.254.169.254`), localhost admin ports, and the
  internal network.
- **Fix:** Add an optional `yukta.http.allowed-hosts` config, block link-local and loopback
  addresses by default, and disable redirects or re-check after each redirect.

### 2.2 Resource safety and correctness

#### G8 · P1 · process-executor runs up to 256 processes at once per node — Effort S
- **Where:** `ProcessExecutorPlugin.process` uses `input.flatMapSequential(...)`, whose default
  concurrency is 256.
- **Impact:** A trigger or splitter that emits many messages forks hundreds of processes at once.
- **Fix:** `flatMapSequential(fn, maxConcurrency)` with a config `maxConcurrency` defaulting to 1.

#### G9 · P1 · A timeout leaves grandchild processes running — Effort S
- **Where:** `cleanupProcess` calls `process.destroy()` and then `destroyForcibly()` on the direct
  child only.
- **Impact:** With `useShell` or scripts (`sh → mvn → java`), descendants keep running, keep the
  pipes open, and block the stdout reader threads on `boundedElastic` indefinitely.
- **Fix:** `process.descendants().forEach(ProcessHandle::destroy)` before destroying the parent,
  and the same in the forcible path.

#### G10 · P2 · Large process output flows through exceptions and logs — Effort S
- **Where:** `executionFailure` and `toLineStream` put **all** stdout (up to 10 MB) into the
  exception message and the error log. Core then echoes payloads into the log stream (core P3),
  and web leaks exception messages (web W2).
- **Fix:** Truncate the text embedded in exceptions to the last N lines (for example 50).

#### G11 · P2 · Script, template and expression caches are unbounded — Effort S
- `MapperProcessor.templateCache` and `jsSourceCache` (singleton, keyed by script text) and
  `ConfigVariableSource.staticValueCache` grow for every distinct workflow ever submitted.
- **Fix:** A bounded LRU, or clear entries from `shutdown(config)`.

#### G12 · P2 · ConfigVariableSource relies on `prepare()`, which is never called — Effort S
- **Where:** `ConfigVariableSource.prepare` fills `staticValueCache`, but core never calls
  `prepare` (plugin-api A7). The cache is always empty. It works by accident because `start`
  resolves the values again.
- **Fix:** Delete `prepare` and the cache.

#### G13 · P2 · LoopStream: RETRY and SKIP behave the same, and ESCALATE wraps in a plain RuntimeException — Effort S
- **Where:** `LoopStreamProcessor.handleFailure`: RETRY and SKIP both advance with the same
  message. ESCALATE throws `RuntimeException("WorkflowExecutionException: …")` rather than the
  real type.
- **Fix:** Give SKIP a distinct meaning (end the loop and emit the last good message) and throw a
  real `WorkflowExecutionException`.

#### G14 · P2 · Loop plugins call another plugin directly and skip its validation — Effort M
- **Where:** `LoopStreamProcessor` and `LoopPredicateProcessor` look up `targetPluginId` in
  `PluginRegistry` and call `process(...)` with `targetConfig`. The target's `validateConfig` and
  `initialize` are never run for that config.
- **Fix:** Validate `targetConfig` through the target's `validateConfig` in the loop's own
  `validateConfig`. Longer term, model loops as sub-graphs in core.

#### G15 · P2 · Internal-core plugins stamp history themselves (messaging M1) — Effort S
- Remove `withAddedHistory(nodeId)` from Branch, RecipientList, Splitter, ContentFilter and
  Filter. Core stamps every node.

#### G16 · P3 · `ApiTriggerPlugin` throws on a missing context payload — Effort S
- `ctx.get("payload")` throws `NoSuchElementException` if the orchestrator didn't write a payload.
  Use `ctx.getOrDefault("payload", Map.of())`.

#### G17 · P3 · session-memory has no eviction *(WIP)* — Effort S
- This is already marked with a `ponytail:` comment. Add Caffeine `expireAfterAccess` (Caffeine is
  already on core's classpath) once multi-user chat is used.

### 2.3 Layering, build and quality gates

| ID | Finding | Fix | Effort |
|---|---|---|---|
| G18 | Plugins depend on `implementation(project(":core"))` to get `VariableResolver`, `MapUtils`, `PluginRegistry` and `DefaultTaskTrackerService` | Move `VariableResolver`, `MapUtils` and `SecretProvider` use into `plugin-api` (or a new `plugin-support` module). Pass the registry and tracker in through narrow SPI interfaces | M |
| G19 | internal-core disables `pmdMain`, `pmdTest`, `checkstyleTest` and `jacocoTestCoverageVerification`, and sets `MapperProcessor` to 0% | Turn them back on one at a time, starting with JaCoCo for `MapperProcessor` after G1 | M |
| G20 | internal-core's coverage config names `AggregatorProcessor`, `ResequencerProcessor`, `SubWorkflowProcessor`, `EnricherProcessor` and `util.MapMessageMapper`, none of which exist | Delete the stale entries | S |
| G21 | `ProcessExecutorGateway.execute(...)` and `executeStream(...)` have no callers outside the gateway's own tests | Delete them. `executeForResult` is the API | S |
| G22 | `UiDesign` HTML is duplicated per plugin and almost identical (colour, icon, label) | Return `UiDesign` from `(colour, icon, label)` and let the UI render it (see plugin-api A13/A14) | S |
| G23 | `ConsoleTerminalPlugin` logs every payload at INFO | Use DEBUG, or truncate | S |
| G24 | `new ObjectMapper()` as a static in several plugins | Inject Spring's configured `JsonMapper`, which keeps date and module config consistent | S |
| G25 | Plugin type IDs use mixed naming: `api-trigger`, `CONTENT-FILTER` and `SCREAMING_SNAKE` everywhere else | Standardise on `SCREAMING_SNAKE` and accept the old IDs as aliases in `PluginRegistry` for one release | S |

---

## 3. Roadmap

| Phase | Items | Outcome |
|---|---|---|
| **1. Close code execution** (1 day) | G1, G2, G3, G4 | No code execution from workflow config |
| **2. Contain resources** (1 day) | G8, G9, G10, G11 | Bounded processes, output and memory |
| **3. Secrets** (½ day) | G5, G6, G7 | Keys out of workflow JSON, and SSRF guard |
| **4. Correctness** (1 day) | G12, G13, G14, G15, G16, G17 | Predictable loops and lineage |
| **5. Layering and gates** (2 days) | G18, G19, G20, G21, G22, G23, G24, G25 | Plugins depend only on plugin-api, gates back on |
