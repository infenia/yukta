# 09 Backlog: reviewed but not implemented

Source: the core review of 2026-10-12 (plan `review-the-curret-implementation-cheeky-candy`). Done items
are in PRs #339-#346 and later; this file lists what is still open, with enough detail to pick it up.
IDs: `B` bug, `S` security, `F` feature. Status: **open**, **needs decision**, **dropped**.

Re-verified on 2026-10-13 against `main@e8d62722`; new findings and the prioritized order are in
[11-remediation-plan.md](11-remediation-plan.md). Corrections since the first write-up: B4 is partly
done (limits and priority are logged, still not reviewed), B19 is fixed for starts and restarts (open
only for sub-workflow starts), B21 is partly done (finished runs capped at 100 per session, session
entries never removed), B25 is partly done (a supervisor's own command outcomes are filtered, status
events are not). B32 and S9 are fixed.

## Bugs and hardening

| ID | Status | What | Where | Why not done / what is needed |
|---|---|---|---|---|
| B4 | needs decision | `changeLimits`, `changePriority` and `POST /control/workflows/{wf}/nodes/{node}/command` skip the `ControlReviewer` and (for the last) the audit; the node command is keyed by workflow+node, not execution | `DefaultControlBusGateway`, `web/.../ControlBusController` | Needs new command types (`SetLimits`, `SetPriority`) in the sealed `ExecutionControlCommand` of `plugin-api` plus processors, i.e. an API change |
| B16 | open | A subtree that stops reading (its terminal failed) stalls sibling branches through `publish` back-pressure; run only fails at the idle timeout | `stream/NodeBroadcast`, `StreamTopologyDecorator` | Needs a fail-fast design: on a downstream cancel-with-error, propagate to the run |
| B21 | needs decision | `sessionStates` / `latestExecs` of finished sessions are never evicted | `DefaultTaskTrackerService` | Evicting sessions drops history the UI shows; decide a max-sessions or TTL policy |
| B25 | open | A supervisor sees the status events its own command causes (pause -> PAUSED) and can loop, capped only by the rate limit | `SupervisorHost` | Needs a way to tag events caused by a command with its actor; no reproducing test yet |
| B27 | open | `DefaultTaskTrackerService` is ~1000 lines; run status has no forward-only guard like node status | `tracker/` | Pure refactor (extract history/cleanup projection); keep the 100% coverage gate |
| B19 | open | `archive()` clears checkpoint files on the start path (blocking) before the run's `subscribeOn` | `WorkflowOrchestrator.execute` | Defer the whole `execute` call; existing gateway tests verify it synchronously |
| B26 | open | Preparator duplicates edges if the prepare `Mono` is subscribed twice; validator and assembler disagree on what a terminal is (`getCategory()` vs `instanceof`); a failed `checkpointStore.save` fails the node while a failed `markComplete` does not | `WorkflowPreparator`, `WorkflowValidator`, `StreamTopologyDecorator` | No caller re-subscribes today; align the checks |
| B23 | dropped | Live log stream stays open until cleanup | `DefaultTaskTrackerService.getLogStream` | An existing test requires the trailing line after the terminal status |
| R1 | open | Restarting a finished run has the same check-then-acquire race that #340 fixed for starts | `RestartCommandProcessor`, `RestartFromNodeCommandProcessor` | Reserve the slot before returning the new id |
| S3 | open | `FileSystemPluginLogReader.findExecutionLogFile` does not validate `executionId` (path traversal); the writer has a create/overwrite race | `logging/impl/file` | Not wired as beans today; fix before wiring |
| S4 | open | `yukta.process.allowed-commands` is opt-in; with it unset any command can run | `ProcessExecutorGateway` | Making it deny-by-default is a breaking change; decide per release |

## Features (priority order)

| ID | Status | Feature | Module | Notes |
|---|---|---|---|---|
| F2 | partly done | `WAIT_FOR_SIGNAL` node + `SignalNodeCommand` (REST/MCP) are done. **Open:** the wait is in memory (a resumed run waits again; make it durable with the checkpoint store), a signal sent before the node waits is refused (no queued signals), no UI for pending approvals | core, plugins, web, mcp | See core/CLAUDE.md "Signals and approval steps" |
| F3 | partly done | REST roles per API key (`yukta.api-keys`, VIEWER/OPERATOR/ADMIN) and actor `key:<name>` in the control audit are done. **Open:** roles for MCP (`yukta.mcp.api-key` is one key), per-session/tenant scoping, key rotation or hashing, a way to see who did what in the UI | web, mcp, core | See web/CLAUDE.md |
| F4 | partly done | Per-run token/cost `budget` on `LLM_CALL` is done (see core/CLAUDE.md). **Open:** per-provider rate limit (calls per minute shared by nodes), budgets across other plugins (MCP tool calls), budget visible in run progress/metrics, hard stop of the whole run instead of a failed call | llm-call, core | |
| F5 | partly done | `allowedTools` on `MCP_TOOL_CALL` is done; approval = put `WAIT_FOR_SIGNAL` in front. **Open:** `deniedTools`/per-tool argument rules, allowlist at the MCP gateway level (all nodes), input/output filters for `LLM_CALL` (prompt-injection checks), a built-in approval-per-tool option | plugins | |
| F6 | partly done | `SUB_WORKFLOW` node and `SubWorkflowGateway` are done (see core/CLAUDE.md). **Open:** a slot-limit deadlock when `max-concurrent` is too small (child waits for the parent's slot; consider letting a child borrow its parent's slot), parent/child links in run history and the UI, cancelling the children when the parent is stopped, runtime-generated sub-DAGs (plan from an agent) | core, plugins | Basis for runtime plans |
| F7 | partly done | `SESSION_MEMORY` history is durable with `yukta.session-memory.dir` (atomic per-session JSON file, fsynced). **Open:** `KV_STORE` is still in memory; session history has no eviction or TTL and loads whole files; no shared/vector memory for agents; a DB backend (see F10) | plugins | |
| F8 | partly done | `LLM_CALL` `tools` option (list or expression) sends tool definitions to OpenAI-compatible/Anthropic/Ollama models and returns their native tool calls in `actions` as `{tool, arguments, id}`; `MCP_LIST_TOOLS` discovers tools with `tools/list` (first page). **Open:** feeding results back as proper `tool` role turns (today a plain user turn), pagination of `tools/list`, streaming, parallel tool calls policy, a ready-made agent-loop workflow example | llm-call, mcp-tool-call | See plugins/CLAUDE.md |
| F9 | partly done | Durable definitions (`FileWorkflowDefinitionStore`), the newest 20 versions per workflow, `GET .../versions` and `POST .../rollback` are done. **Open:** MCP tools for versions/rollback, diff between versions, names/notes on versions, pinning a run to a version, a UI for history | core, web | See core/CLAUDE.md |
| F10 | partly done | Restart spill (`yukta.restart.spill-dir`) landed; a `RESUMABLE` status was skipped on purpose (`ABORTED` is the resume candidate, a new value would spread into web/UI status vocab). `persistence-postgres` (R2DBC at run time, Flyway over JDBC for the schema; user decision) backs sessions, workflow definitions + versions and the execution log; tested with Testcontainers and a full-app test. **Open:** the node checkpoint store in PostgreSQL (`NodeCheckpointStore` has blocking `isFinished/isComplete`; large payloads need a claim-check/TOAST decision), idempotency keys already ride the log, a CI job that runs the PostgreSQL acceptance test with `-Pyukta.persistence=postgres`, native-image reachability metadata for R2DBC/Flyway, connection-pool tuning and health checks, row-level retention for session configs, Flyway license check before distribution (Community edition, allowed per artifact in the build conventions) | new module | Prerequisite for HA |
| F11 | design written | Remote workers (decision: control plane schedules nodes, workers run them). Design and phases in `10-remote-workers.md`; **no code yet**. Waiting on the open decisions at the end of that document (transport, packaging, secrets, first plugins, payload limit); control plane HA is phase 6 | messaging, core | Large |
| F12 | open | Saga compensation, node result caching, A2A agent card, eval/scoring node, backfill, run history search, MDC log correlation, Vault secrets, `yukta.restart.auto-resume` | various | Pull in on demand |
| F13 | needs decision | Trace id in the progress snapshot (REST/SSE/MCP). Needs `micrometer-tracing` on the core classpath, which core does not depend on; MDC was dropped on purpose (structured key-values carry `executionId`/`nodeId`) | `RunTelemetry`, `WorkflowProgress` | Add the dependency only if a tracing backend is used in production |
