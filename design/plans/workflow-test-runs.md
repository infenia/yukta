# Test runs from the workflow editor

Plan for running a workflow **draft** from the editor, without saving it: "Run up to here" (a node and everything before it) and "Test this step" (one node, fed a sample). It is phase P6 of the editor rework (#2570, #2572, #2573, #2576, #2578, #2580); P7, a picker that inserts references to earlier steps' output, builds on it.

Status (2026-11-21): **proposed, nothing built.** Arun decided on 2026-11-21 that the server gets a new endpoint (not a UI-side save of a temporary workflow) and that a test run is invisible to the rest of the product. The "today" statements were read from the code of this branch.

## Today

- A run starts from a **stored definition**: `WorkflowStarter.startOnce` loads it with `definitions.find(sessionId, workflowId)` and answers 404 without one, then calls `prepareAndExecute`. The editor can validate a draft (`POST /api/sessions/validate`) but not run it.
- `prepareAndExecute` takes the definition as an argument. With `useCache=false` it prepares the workflow and does not cache it (`prepareShared` caches only a definition that equals the stored one), so a draft can be run without storing it.
- Every run writes its events to the `ExecutionLogStore`, which has three operations: `append`, `readAll`, `replaceAll`. Its readers are history and search (`ExecutionController`), recovery after a restart (`ExecutionRecovery`, `ExecutionResumer`), the supervisors' replay (`SupervisorHost`, `ReplayEvents`), analytics (`RunAnalytics`) and the control audit.
- A run's progress and node outputs are read through the existing run endpoints (`GET /api/executions/{id}/nodes/{nodeId}/output`, checked by `ExecutionAccess` against the control bus's progress), which come from the in-memory tracker, not the log.

## Design

1. **Reserved workflow ID.** A test run uses the real session (its project path, tags and credentials are what the nodes need) and a workflow ID `__test__.<uuid>`. Nothing is stored under it.
2. **Endpoint.** `POST /api/sessions/{sessionId}/test-runs` with `{workflow, untilNodeId?, payload?}` answers `202 {executionId, workflowId}`. It sits under `/api/sessions/`, so it needs the ADMIN role, as saving a workflow does; running a draft is the same power as saving and starting it. A refused start (limits) is 429 as for a normal start; a draft that does not validate is 422 with the problems.
3. **Pruning.** With `untilNodeId`, the run is that node and its ancestors; every other node and edge is dropped. When the node is not a terminal, a synthetic terminal (`CONSOLE_TERMINAL`, node ID `__test__.sink`) is connected from it on all ports (`sourcePort "*"`), so the pruned graph passes the same rules as any workflow (a processor must have an outgoing edge, a branch must end in a terminal).
4. **Test this step.** The run is a synthetic trigger (`API_TRIGGER`) that emits the start payload, then the node, then the sink. The payload is the sample the editor sends. Its ancestors are not run.
5. **Invisible.** A wrapper around the `ExecutionLogStore` (`append` drops events whose workflow ID starts with `__test__.`) keeps test runs out of history, search, recovery, supervisor replay, analytics and the audit with one change. The wrapper is applied where the store is built, so it covers every store (memory and Postgres). The run's progress and outputs stay in the in-memory tracker, readable through the existing run endpoints until the tracker's retention drops them.
6. **What stays visible, on purpose.** While it runs, a test run counts against the scheduler like any run and shows on the live event stream. The reserved ID is never assigned to a supervisor; a supervisor that owns a whole session could still see its live events (to check in the implementation, see below).
7. **UI** (a later PR): "Run up to here" and "Test this step" in the inspector, an Output tab with the node's output as JSON, the last sample of each node kept ("pinned") in the draft's session storage and shown as a chip on the node. P7 adds a `{}` button on expression fields that lists the direct ancestors and the paths of their pinned sample, and inserts `payload.<path>`.

## Phases

| | What | Notes |
|---|---|---|
| B1 | Pruner (pure, tested), the log wrapper, `WorkflowService` / `WorkflowStarter` entry for a draft, the controller, the OpenAPI snapshot and API types | Java, 100% per class; the largest part |
| B2 | Editor: the two buttons, Output tab, pinned samples | UI |
| B3 | The field picker (P7) | UI, on B2 |

## To check while building

- That `API_TRIGGER` and `CONSOLE_TERMINAL` are enabled in every build the editor can talk to (`yukta.plugins.enabled`); if not, use whichever core trigger and terminal are.
- Where `SupervisorHost` gets its events: from the log store (then the wrapper hides test runs from it) or from the tracker (then a session-wide supervisor can see them live, and the host should skip the reserved prefix).
- That the tracker drops finished test runs on its normal retention, and that `ConfigRedactor` covers the outputs the endpoint returns.
- The synthetic sink for a node with a port nothing reads: the validator should only warn (it does for the editor today).
