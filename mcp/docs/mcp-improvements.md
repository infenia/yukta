# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited

# mcp — Review, Improvements & Optimisations

> Scope: `mcp/src/main` (40 files, ≈2.3k lines) reviewed on 2026-10-09 against `main` @ `8d3161b5`.
> Companion: [`mcp-module-guide.html`](mcp-module-guide.html) is the interactive guide, including a tool catalogue.

## 1. Verdict

`mcp` exposes Yukta to AI agents as 14 tools, 2 prompts and 3 resources on a stateless, async
Spring AI MCP server. It calls core services directly, with no dependency on `web`.

Tool design is good:
- Descriptions are agent-oriented.
- Read-only and destructive hints are set.
- Errors carry next-step guidance ("Use get_workflow_history to list executions").
- Session ownership is checked on every execution-scoped tool, which is stricter than `web`.
- The Spring AI pitfalls are worked around and documented (wrapper records instead of `Flux` or
  `Mono<List>`).

The weaknesses:

- **The terminal-status vocabulary is wrong.** `DefaultSystemHealthProvider` treats
  `COMPLETED/FAILED/CANCELLED/WORKFLOW_STOPPED` as terminal, but core emits
  `SUCCESS/FAILURE/ERROR`. **Every finished execution is counted as active.**
- **Old executions become unreachable.** Ownership is checked against the tracker's in-memory
  index, which is evicted 10 minutes after a run ends. After that, `get_execution_logs` and
  `get_workflow_status` say "Execution not found", even though the logs are still stored.
- **The regex filter is a CPU risk.** The agent-supplied pattern is compiled once per log line, and
  there's no guard against catastrophic backtracking.
- **`create_session` accepts a raw JSON string** and is marked non-destructive. It's the same
  unauthenticated workflow-submission path as `POST /api/sessions` (web W1).
- **There's some unneeded abstraction.** Six provider interfaces have one implementation each,
  and `AppMcpTools` injects the concrete classes anyway. About 18 DTOs duplicate `web`'s.

### Scorecard

| Area | Score | Notes |
|---|---|---|
| Tool design | 4/5 | Clear names, hints, actionable errors, consolidated `control_*` tools |
| Correctness | 2.5/5 | Wrong terminal statuses. Ownership check stops working after the TTL |
| Security | 2/5 | No auth. Regex denial-of-service. `create_session` under-hinted |
| Performance | 3/5 | Per-line regex compile. Whole log read into memory. N+1 health queries |
| Simplicity | 3/5 | Unused interfaces, DTO duplication |
| Tests | 4/5 | 17 test classes, including a real JSON-RPC integration test |

---

## 2. Findings

Severity: **P0** fix now · **P1** fix next · **P2** should fix · **P3** clean-up. Effort: S < ½ day, M ≈ 1–2 days.

### 2.1 Correctness

#### P-1 · P1 · Health status counts finished executions as active — Effort S
- **Where:** `DefaultSystemHealthProvider.TERMINAL_STATUSES = {"COMPLETED","FAILED","CANCELLED","WORKFLOW_STOPPED"}`.
- **Impact:** Core's terminal statuses are `SUCCESS`, `FAILURE` and `ERROR` (`TaskStatus`). So
  `get_control_bus_status` reports every historical execution as active. An agent will think
  workflows are still running.
- **Fix:** Use `TaskStatus.valueOf(s).isTerminal()`, made tolerant of unknown values (core C1).
  Better: core exposes `isTerminal` on `WorkflowExecutionSummary`. Add a test with real status
  values.

#### P-2 · P1 · Executions become "not found" 10 minutes after they finish — Effort S
- **Where:** `requireOwnership` (in `DefaultLogProvider`, the workflow provider and the control
  provider) → `ControlBusGateway.getCurrentProgress` → `tracker.getProgressByExecutionId`. That
  reads `executionIndex`, which core removes after `cleanup-ttl` (10 min).
- **Impact:** `get_execution_logs` and `get_workflow_status` fail for any execution older than
  10 minutes, even though `get_workflow_history` still lists it and the log store still has the
  lines. The agent gets contradictory answers.
- **Fix:** Check ownership with `tracker.getProgress(sessionId, executionId)`, which reads the
  per-session map that survives cleanup. Or check whether the execution is in
  `getHistory(sessionId)`. This is the same root cause as core C13.

#### P-3 · P2 · `create_session` swallows and relabels every error — Effort S
- **Where:** `DefaultSessionInfoProvider.createSession` uses
  `onErrorResume(e -> IllegalArgumentException("Invalid JSON format or missing required fields: " + e.getMessage()))`.
- **Impact:** Validation failures, plugin-not-found errors, file-store I/O errors and bugs all read
  as "invalid JSON". Internal messages also reach the agent.
- **Fix:** Only map `JsonProcessingException` there, and let typed validation errors pass through
  unchanged.

### 2.2 Security

#### P-4 · P1 · Agent-supplied regex: compiled per line, with no denial-of-service guard — Effort S
- **Where:** `RegexPatternValidator.matches` calls `Pattern.compile(pattern)` for **every log
  line**. A pattern like `(a+)+$` causes catastrophic backtracking against long lines.
- **Fix:** Compile once in `getExecutionLogs` and pass the `Pattern` in. Cap the pattern length at
  about 200 characters. Wrap the `CharSequence` with a length or time budget, or offer a plain
  substring filter (`contains`) as the default and regex as opt-in.

#### P-5 · P1 · `create_session` is unauthenticated workflow submission and is marked non-destructive — Effort S
- **Where:** `AppMcpTools.createSession` has `destructiveHint = false`. The MCP endpoint
  (`/sse`, streamable HTTP) shares the server's lack of auth (web W1).
- **Impact:** Any MCP client can install workflows, and with plugin-api A1 (SpEL) or
  process-executor, run code. Agents and hosts use the hints to decide whether to ask the user for
  confirmation.
- **Fix:** Set `destructiveHint = true` (it overwrites existing sessions) and
  `openWorldHint = true` when workflows can reach external systems. Protect the MCP endpoint with
  the same API key as REST.

### 2.3 Performance

| ID | Finding | Fix | Effort |
|---|---|---|---|
| P-6 | `get_execution_logs` reads the whole log into a list, then tails it | `filter(...).takeLast(tailLines)` before `collectList()`. Also add a hard cap (for example 5,000 lines) so one call can't return megabytes into an agent's context | S |
| P-7 | `get_control_bus_status` does N+1 work: for every session, read its config and its history | Ask core for a one-pass summary. Until then, put a `flatMap` concurrency limit on it | M |
| P-8 | `Mono.fromCallable(...).subscribeOn(boundedElastic())` around in-memory calls (`listPlugins`, `getCurrentProgress`) | Drop the thread hop for non-blocking calls | S |
| P-9 | `threadPoolUtilization = Thread.activeCount() / cpus * 100` doesn't measure anything meaningful and can exceed 100% | Remove it, or report real scheduler metrics through Micrometer | S |

### 2.4 Simplification

| ID | Finding | Fix | Effort |
|---|---|---|---|
| P-10 | Six provider interfaces (`LogProvider`, `PluginInfoProvider`, …) each have one implementation, and `AppMcpTools` injects the `Default*` classes directly | Delete the interfaces and rename the classes without the `Default` prefix | S |
| P-11 | About 18 DTOs duplicate `web`'s (`PluginDetails`, `PluginSummary`, `SessionCreationGuide`, `ExecutionRecord`, `SystemHealthMetrics`, …). `ErrorExample` is a Swagger artefact with no use in MCP | Move the shared read models to core `model.api`, or accept the duplication deliberately and delete `ErrorExample` | M |
| P-12 | The MCP endpoint is named `/sse` but uses streamable-HTTP transport | Rename it to `/mcp` and keep `/sse` as an alias if clients are already configured | S |
| P-13 | `create_session` takes `sessionConfigJson: String`, so agents must hand-escape nested JSON | Take a structured `SessionConfigData` parameter, which Spring AI generates a schema for. The agent gets field-level validation | S |
| P-14 | `control_node` offers SKIP and STEP, which have no effect in core (core C5/C6) | Hide them from the tool description until core fixes them, or return an explicit "not supported" | S |

---

## 3. Roadmap

| Phase | Items | Outcome |
|---|---|---|
| **1. Correct answers** (½ day) | P-1, P-2, P-3, P-14 | Agents see true status and can reach old executions |
| **2. Safety** (½ day) | P-4, P-5, P-6 | Bounded CPU and output, honest hints |
| **3. Trim** (1 day) | P-10, P-11, P-12, P-13, P-8 | Fewer types and structured input |
| **4. Scale** | P-7, P-9 | Health tool cost doesn't grow with session count |
