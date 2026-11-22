# Token-efficient run queries for AI agents (search, digest, bounded defaults)

Status (2026-11-22): **implemented** in infenia/yukta#2614 (all seven phases) and infenia/yukta-dogfood#24 (fixers read the
digest first). The phases below are the plan as approved; "Built differently" lists where the code departs from it.

## Built differently

| Plan | Built | Why |
|---|---|---|
| Search in `core/.../logging/query/` | `core/.../service/diagnosis/` (`RunLogSearcher`, `LogSearchPass`) | The core package diagram does not let `logging` use `service` (the tracker, for sub-runs). |
| Guarded `java.util.regex` with a 50 ms per-line budget (`GrepPattern`) | `GrepPattern` wraps RE2/J (`com.google.re2j`, BSD-3-Clause), a linear-time engine | The per-line budget still let one call run for minutes over 10,000 lines, and CodeQL flagged `java.util.regex` on caller input. RE2 has no back-references or lookaround. |
| Sub-runs searched as found in the tree | Only sub-runs of the run's own session (search and digest) | A sub-run of another session must not be readable through a parent the caller owns. |
| Findings carry a log sequence | Findings carry the line number in the node's output | `next` reads point at `get_node_output` offsets, which count node lines. |
| `TestFailure:` lines only | `TestFailure:` and `LintFailure:` lines | `reports` already read Checkstyle and SARIF; their findings become LINT findings. |
| Parsing of stream/level/nodes in each client | `SearchArguments` in core | REST, MCP and the log filter read the arguments the same way. |
| `get_workflow_status` `nodes=failed\|all` | `hideSucceeded` (boolean) | Simpler; the default stays every node. |
| Not planned | `get_node_output`: a page without `limit` is cut to `yukta.mcp.log-default-chars` | `ToolResponseBoundTest` found a default page could reach 100 KB. |
| Not planned | `yk` fixers: `MCP_READ` starts with the digest and the search (dogfood #24) | The planned follow-up. |
| Phase 6: "update the dogfood-facing example" | `examples/1-standalone/02-release-approval`: the `test` step writes a JUnit report and reads it with `reports` | The dogfood workflows live in yukta-dogfood; this example is the one in this repository that runs tests. |

## Measured

- `ToolResponseBoundTest` (15 MB run): every read with default arguments under 20 KB, the digest under 6 KB.
- `McpRunDiagnosisE2eTest` (real server, small run): the digest names the failing test and its `file:line` and is over 3x smaller than
  the whole log.
- `RealGradleFailureMeasureTest`: the Gradle step of a real CI run of this repository that failed four tests (1,330 lines, 117 KB),
  replayed as one node's log. The digest is 1,781 bytes and names the four tests and the failed task; the whole log through
  `get_execution_logs` is 203,797 bytes (**114x**), and the default `get_execution_logs` (last 100 lines) is 14,948 bytes and does not
  reach the failures. The test fails below 10x. It also caught javac `warning:` lines being taken for PMD findings.

## Not done on purpose

- `yukta.mcp.response.bytes` covers keyed callers only (the open loopback endpoint does not read the request body).
- `get_execution_result` with `include=stdout` holds back events past the budget, but one payload larger than it is returned whole.
- No UI panel for the digest.

## Context
Agents connected to Yukta (MCP, REST, CLI) spend many tokens because the default way to read a run is
expensive and the cheap tools are not the obvious first choice:
- `get_execution_logs` with no arguments returns up to 5000 lines / 200 KB (`LogProvider.MAX_LINES`,
  `yukta.mcp.log-max-chars`); the logs MCP resource always returns that maximum.
- `get_execution_result` with `include=stdout` has no size cap; `get_execution_tree`,
  `get_workflow_history` have no limit; `get_workflow_status` has no field selection.
- A grep already exists, but only per node: `get_node_output` → `NodeOutputPager` (grep, context,
  head/tail/offset, 2000-char lines, regex time budget).
- `LogLineClassifier.problems()` already extracts ~20 error lines per failed node, but only into event
  metadata; no tool returns a failure summary.
- No server-level MCP `instructions`; the `debug-workflow` prompt sends agents to `get_execution_logs`.

No architecture change is needed: logs are in memory (≤10k lines/execution, `InMemoryPluginLogStore`),
a scan is cheap. We add a query layer in `core`, expose it on MCP/REST/CLI, shrink defaults, and steer
agents to a **digest → search → page** funnel. A full-text index is out of scope (only worth it with a
persistent log store beyond 24 h retention).

Decisions (user, 2026-11-22): full scope incl. test-failure parsing; tighten existing defaults;
search covers sub-runs behind an opt-in flag.

## Working setup
Implement in a **new worktree** off `origin/main` (`EnterWorktree`, branch `feat/agent-run-search`),
not on the current `fix/nightly-native-signals-pitest` branch. One PR per phase, stacked. First
commit: copy this plan to `design/plans/agent-efficient-run-queries.md`.

## Phase 1 — core: run log search (`core/.../logging/query/`)
- Extract the regex guard from `NodeOutputPager` (`pattern()`, `matches()`, `Budgeted`, `MAX_PATTERN`,
  `MATCH_BUDGET_NANOS`, `cut`) into a public `@UtilityClass GrepPattern` in `logging/api`; `NodeOutputPager`
  delegates to it (no behaviour change, its tests stay green).
- `RunLogQuery` record (`@Builder`): `executionId`, `includeChildren`, `nodes` (set), `stream`
  (stdout|stderr|all), `level` (ERROR|WARN|INFO, minimum), `grep`, `context` (≤20), `mode`
  (`COUNT` | `NODES` | `LINES`, default `COUNT`), `maxMatches` (default 50, max 500), `maxBytes`
  (default 16 KB, hard 64 KB), `cursor` (opaque `exec:node:line`).
- `RunLogSearcher` (Spring service): reads `PluginLogStore.readIndexed` per execution; groups by node
  (`pluginId`); rates each node's lines with its own `LogLineClassifier` instance, in order; filters by
  stream/level/grep with context; children resolved through the same core lookup that backs
  `get_execution_tree` (`WorkflowExecutionProvider` → core `ExecutionQueries`/history; reuse it, do not
  duplicate). Stops at `maxMatches`/`maxBytes`.
- `RunLogSearchResult`: `summary` (executions and lines scanned, total matches, per execution+node:
  matches, errors, warns, lines), `lines` (LINES mode: execution, node, lineNo, stream, level, text cut
  to 2000), `truncated`, `nextCursor`, `hint` (the exact narrower call to make next).
- Tests: `GrepPatternTest`, `RunLogSearcherTest` (StepVerifier; modes, level rating across a gradle
  "What went wrong" block, children, cursor resume, byte cap, bad/expensive regex).

## Phase 2 — core: failure digest and failure extractors (`core/.../service/diagnosis/`)
- `FailureExtractor` interface (`List<Finding> extract(String nodeId, List<String> lines)`), `Finding`
  record: `kind` (TEST | COMPILE | LINT | TASK | ERROR), `name`, `location` (file:line), `message`,
  `lineNo`. Implementations, each a small regex parser over console output:
  - `GradleExtractor`: `> Task :x FAILED`, `What went wrong` + cause, `Class > method() FAILED` + the
    indented assertion line, `File.java:12: error:`, checkstyle/PMD/spotbugs `[ERROR] file:line`.
  - `GoTestExtractor`: `--- FAIL: TestX`, `file_test.go:12:` lines, `FAIL pkg`.
  - `JsTestExtractor`: vitest/jest `FAIL path`, `×`/`✕` test names, `AssertionError`.
  - `ReportLineExtractor`: lines prefixed `TestFailure:` (written by phase 6), exact parse.
  - Fallback: `LogLineClassifier.problems()`.
- `FailureDigestService.digest(sessionId, executionId)`: from `ControlBusGateway.getProgress`
  (status, failed/timed-out nodes, `NodeStreams.ERROR_KEY` metadata), walks failed sub-runs to the
  deepest failure, per-node line/level counts from `RunLogSearcher` COUNT mode, top findings
  (deduplicated, ≤20), and `next` — concrete follow-up calls (`search_logs` with node+grep,
  `get_node_output` with offset around the first finding). Hard cap 4 KB; running executions return a
  partial digest with `complete=false`.
- Tests per extractor with real captured snippets (Gradle, Go, vitest) under `src/test/resources`,
  and `FailureDigestServiceTest`.

## Phase 3 — MCP: new tools, instructions, guidance (`mcp`)
- `AppMcpTools`: `get_failure_digest` (sessionId, executionId) and `search_logs` (sessionId,
  executionId, includeChildren, nodes, stream, level, grep, context, mode, maxMatches, cursor),
  both `readOnlyHint`, VIEWER in `McpToolAccess`, ownership via `ExecutionOwnership`, output lines
  through `LogSanitizer` (redaction), MCP DTO wrapper records in `mcp/dto` with `@JsonInclude(NON_NULL)`
  and `@Nullable` (see `ToolOutputSchemaTest`).
- Descriptions say when to use them: digest "Call first for a failed or finished run"; search_logs
  "Prefer over get_execution_logs; start with mode=COUNT".
- Server instructions: `spring.ai.mcp.server.instructions` in `boot/src/main/resources/application.yaml`
  (verify the Spring AI 2.0 property; else a server customizer bean in `mcp`): the funnel
  digest → search_logs → get_node_output, never whole logs, untrusted-data rule.
- Rewrite the `debug-workflow` prompt in `AppMcpPrompts` to the funnel.
- Rows in `docs/reference/mcp-tools.mdx` (`ToolTextDriftTest`), `McpDtoReflectionHints` for native.

## Phase 4 — MCP: bounded defaults on existing tools + measurement
- `get_execution_logs`: default `tailLines` 100 when neither tail nor cursor is given; default char
  budget 16 KB (`yukta.mcp.log-default-chars`), explicit requests still up to the existing hard caps;
  add `nodeId` and `level` params; a cut answer carries `hint`. Logs resource (`AppMcpResources`)
  uses the same defaults.
- `get_execution_result`: per-call char budget (`yukta.mcp.result-max-chars`, 32 KB), payload fields
  cut with `truncated` + `nextCursor`.
- `get_execution_tree`, `get_workflow_history`: `limit` (default 50) and `status` filter;
  `get_workflow_status`: `nodes=failed|all` (default all; failed returns only non-success nodes).
- `read_agent_screen`: optional `tailRows`.
- Measurement: `McpEndpointFilter` records `yukta.mcp.response.bytes{tool}` (distribution summary);
  it already knows the tool name for the heavy limiter. Add to `get_metrics_snapshot`.
- `ToolResponseBoundTest`: fill the store with 10k long lines + big results, call every read tool with
  default arguments, assert each response ≤ 20 KB (digest ≤ 4 KB).
- Update `docs/contributing/core-reference.mdx` settings/meters (run `core/scripts/check-core-reference.sh --stamp`).

## Phase 5 — REST and CLI parity
- `web`: `GET /api/sessions/{s}/executions/{e}/logs/search` and `GET .../digest` (new controller next to
  `NodeOutputController`, session ownership like `LogManagementController`), response DTOs.
  Refresh API snapshots in the same PR (`OpenApiSnapshotTest -Pyukta.openapi.update=true`,
  `generate-api-types.mjs`).
- `cli`: `yukta-cli logs search <s> <e> [--grep --level --node --children --mode --max]` and
  `yukta-cli digest <s> <e>`; client in `cli/internal/client/` following `GetNodeOutput`; 100% function
  coverage.

## Phase 6 — structured test reports from PROCESS_EXECUTOR
- Opt-in config `reports.junit` (glob relative to the process working dir, e.g.
  `**/build/test-results/**/*.xml`): after the process exits, the plugin parses matching JUnit XML
  (StAX, size-capped, no external entities) and writes one live log line per failure:
  `TestFailure: <class>#<method> | <file:line> | <first message line>` (constant next to
  `NodeStreams.STALLED_PREFIX` style), parsed exactly by `ReportLineExtractor`. Keeps file access in the
  plugin's sandboxed working dir; the server never reads arbitrary paths.
- Update the dogfood-facing example/tool workflow in `examples/` to set `reports.junit`.

## Phase 7 — end-to-end and docs
- `e2e`: a failing-Gradle-like workflow (PROCESS_EXECUTOR printing a captured failure) → MCP
  `get_failure_digest` names the failing test, response ≤ 4 KB; `search_logs` COUNT then LINES.
- Docs guide page "Reading runs efficiently (agents)" under `docs/` with the funnel and examples;
  `docs/contributing/modules/mcp.mdx` updated.
- Follow-up outside this repo (note in PR): yukta-dogfood fixers can call `get_failure_digest` through
  the read-only fixer MCP key.

## Verification
- Per phase, smallest scope first: `yk gradle :core:test --tests '<FQCN>'`, `:mcp:test --tests ...`,
  `:web:test`, `yk tool -C cli go test ./...`, then `yk gradle spotlessApply` and `yk check` (background);
  `yk check --full` before the last PR. Architecture: `yk gradle :architecture:test`.
- Manual: `./gradlew bootRun`, run a failing workflow, call `get_failure_digest`, `search_logs`
  (COUNT → LINES) via the yukta MCP tools; compare `yukta.mcp.response.bytes` for the old path
  (`get_execution_logs` no args) vs the funnel — target ≥10× fewer bytes on a failed Gradle run.
