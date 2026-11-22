# ADR 0012: Test runs from the workflow editor

- **Status:** accepted on 2026-11-21. The backend (B1) is built: `DraftRunController` at `/api/sessions/{sessionId}/test-runs`, `DraftRuns` in `core` and the `__test__` reserved prefix. The editor UI (B2) and the field picker (B3) follow in #2586.
- **Date:** 2026-11-21 (condensed from the workflow-test-runs plan on 2026-11-22)
- **Decided by:** Arun, 2026-11-21: the server gets a new endpoint (not a UI-side save of a temporary workflow) and a test run is invisible to the rest of the product.
- **Plan:** [`design/plans/workflow-test-runs.md`](../plans/workflow-test-runs.md) (kept while phases B1 to B3 are open).
- **Related:** editor rework phase P6 (#2570, #2572, #2573, #2576, #2578, #2580); P7 (field picker) builds on it.

## Context

A run starts from a stored definition: `WorkflowStarter.startOnce` loads it and answers 404 without one. The editor can validate a draft but not run it. `prepareAndExecute` already takes the definition as an argument, and with `useCache=false` it does not cache it, so a draft can run without being stored. Every run writes events to the `ExecutionLogStore`, read by history, search, recovery, supervisor replay, analytics and the audit; progress and node outputs come from the in-memory tracker.

## Decisions

| # | Decision | Outcome |
|---|---|---|
| D1 | Identity | A test run uses the **real session** (project path, tags, credentials) and a reserved workflow ID `__test__.<uuid>`. Nothing is stored under it. |
| D2 | Endpoint | `POST /api/sessions/{sessionId}/test-runs` with `{workflow, untilNodeId?, payload?}` returns `202 {executionId, workflowId}`. ADMIN role (running a draft is the same power as saving and starting it). Limits refusal is 429; an invalid draft is 422 with the problems. |
| D3 | "Run up to here" | The run is the node and its ancestors; other nodes and edges are pruned. If the node is not a terminal, a synthetic `CONSOLE_TERMINAL` (`__test__.sink`) is connected on all ports so the pruned graph passes the normal validation rules. |
| D4 | "Test this step" | A synthetic `API_TRIGGER` emits the sample payload, then the node, then the sink. Ancestors are not run. |
| D5 | Invisible | A wrapper around `ExecutionLogStore` drops `append` for workflow IDs starting `__test__.`, keeping test runs out of history, search, recovery, replay, analytics and audit with one change, applied where the store is built (covers memory and PostgreSQL). Progress and outputs stay readable through the existing run endpoints until the tracker's retention drops them. |
| D6 | Deliberately visible | While running, a test run counts against the scheduler and appears on the live event stream. The reserved ID is never assigned to a supervisor. |
| D7 | UI | "Run up to here" and "Test this step" in the inspector, an Output tab, and the last sample per node pinned in the draft's session storage. P7 adds a `{}` button on expression fields that inserts `payload.<path>` from direct ancestors. |

## Open checks

`API_TRIGGER` and `CONSOLE_TERMINAL` must be enabled in every build the editor talks to; where `SupervisorHost` gets its events (log store hides test runs, tracker does not, so a session-wide supervisor could see them live and the host should skip the reserved prefix); the tracker's retention and `ConfigRedactor` coverage of returned outputs; a synthetic sink on a port nothing reads should only warn.
