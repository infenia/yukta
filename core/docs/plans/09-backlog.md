# 09 Backlog: reviewed but not implemented

Source: the core review of 2026-10-12 (plan `review-the-curret-implementation-cheeky-candy`). Done items
are in PRs #339-#346 and later; this file lists what is still open, with enough detail to pick it up.
IDs: `B` bug, `S` security, `F` feature. Status: **open**, **needs decision**, **dropped**.

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
| F2 | open | Durable wait-for-signal / human-approval node with timeout; `POST /executions/{id}/signals/{name}` | core, plugins, web, mcp | The HITL gate for agent workflows. Today only an in-memory `DELAY` and breakpoints exist |
| F3 | open | Roles per API key (viewer/operator/admin); actor recorded in `CONTROL_*` audit | web, mcp, core | Today one shared key can kill runs or run processes |
| F4 | open | Per-run token/cost budget (`engine.budget`) and per-provider rate limit for `LLM_CALL` | core, llm-call | `usage`/`cost` are per call only |
| F5 | open | Tool guardrails: `allowedTools` per `MCP_TOOL_CALL`/`LLM_CALL` node; approval for risky tools (needs F2) | plugins | |
| F6 | open | `SUB_WORKFLOW` node (child run via `WorkflowStarter`, linked ids) | core, plugins | Basis for runtime plans |
| F7 | open | Durable session memory / KV store (file backend) | plugins | `SessionStore` and `KV_STORE` are in memory |
| F8 | open | Native provider tool calling in `LLM_CALL` and `tools/list` discovery from the MCP gateway | llm-call, mcp-tool-call | Today `<agent_action>` text tags |
| F9 | open | Definition version history and rollback | core | Runs already pin a content hash |
| F10 | open | JDBC persistence behind the log / checkpoint / session store SPIs | new module | Prerequisite for HA |
| F11 | open | Distributed workers / HA (leader-elected scheduler, remote node execution) | messaging, core | Large; needs a design doc first |
| F12 | open | Saga compensation, node result caching, A2A agent card, eval/scoring node, backfill, run history search, MDC log correlation, Vault secrets, `yukta.restart.auto-resume` | various | Pull in on demand |
