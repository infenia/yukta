# Step-Mode REST Endpoints — Design

## Context

`ControlBusGateway` already defines and `DefaultControlBusGateway` already implements
`enableStepMode`, `disableStepMode`, and `stepNode`. These emit `EnableStepModeCommand`,
`DisableStepModeCommand`, and `StepNodeCommand` onto the control bus, mirroring the
pattern used by `pauseNode`/`resumeNode`/`stopNode`/`skipNode`.

Two things are missing:

1. **REST endpoints.** `WorkflowController` exposes `/pause`, `/resume`, `/stop`, and
   `/skip` for a node, but no endpoints for step-mode control. There is no way to drive
   this functionality over the REST API today.
2. **Existence validation.** `pauseNode`, `resumeNode`, `stopNode`, and `skipNode` all
   call a shared `requireNodeControl(executionId, nodeId)` helper that throws
   `IllegalArgumentException("Execution not found: ...")` or
   `IllegalArgumentException("Node not found: ...")` before emitting a command.
   `enableStepMode`, `disableStepMode`, and `stepNode` skip this check — they will
   silently emit a control command for an execution or node that doesn't exist, instead
   of failing fast. This is an inconsistency with the sibling methods, not a
   deliberate design choice (confirmed by reading `DefaultControlBusGateway` and its
   existing unit tests, which don't exercise not-found paths for these three methods).

## Goals

- Add REST endpoints so step-mode can be driven via the API like every other node
  control operation.
- Fix the validation gap so `enableStepMode`, `disableStepMode`, and `stepNode` behave
  consistently with `pauseNode`/`resumeNode`/`skipNode` (404 on unknown execution/node).
- Follow existing conventions exactly: no new abstractions, no new response types.

## Non-goals

- No changes to the control bus command processors (`EnableStepModeCommandProcessor`,
  `DisableStepModeCommandProcessor`, `StepNodeCommandProcessor`) or to
  `ReactiveControlValve` step-mode behavior — these already work and are tested.
- No changes to `ExecutionControlCommand` payload types.

## Design

### 1. `DefaultControlBusGateway` — add existence validation

For each of `enableStepMode`, `disableStepMode`, `stepNode`, replace the current
`executeCommand(...)` entry point with the same shape used by `pauseNode`:

```java
Mono.fromSupplier(() -> requireNodeControl(executionId, nodeId))
    .flatMap(control -> executeCommand(buildCommand(new XxxCommand(executionId, nodeId), CONTROL_COMMAND_PRIORITY)))
    .doOnSubscribe(...)
    .doOnSuccess(...)
    .doOnError(...)
```

No new helper needed — `requireNodeControl` already exists and is reused as-is.

### 2. `WorkflowController` — three new endpoints

Add alongside the existing `pauseNode`/`resumeNode`/`stopNode`/`skipNode` methods,
reusing the private `executeControlSignal(...)` helper (handles progress lookup,
session ownership check, 404 translation for both "Execution not found" and
"Node not found", and `ApiResponse<WorkflowStartResponse>` wrapping):

| HTTP | Path | Gateway call |
|---|---|---|
| `POST` | `/api/workflow/{sessionId}/{executionId}/node/{nodeId}/step/enable` | `controlBus.enableStepMode(executionId, nodeId)` |
| `POST` | `/api/workflow/{sessionId}/{executionId}/node/{nodeId}/step/disable` | `controlBus.disableStepMode(executionId, nodeId)` |
| `POST` | `/api/workflow/{sessionId}/{executionId}/node/{nodeId}/step` | `controlBus.stepNode(executionId, nodeId)` |

Each method:
- Takes `sessionId`, `executionId`, `nodeId` path variables and a `ServerWebExchange`.
- Has `@Operation`/`@ApiResponse` Swagger annotations matching the style of
  `pauseNode`/`skipNode` (200 success + 404 not-found).
- Delegates to `executeControlSignal(operationName, sessionId, executionId, nodeId, successMessage, action, exchange)`.

No new DTOs — response is `Mono<ResponseEntity<ApiResponse<WorkflowStartResponse>>>`,
identical to the other node-control endpoints.

### 3. Tests

**Gateway (`DefaultControlBusGatewayTest`)** — for each of the three methods, add:
- execution-not-found case (unknown `executionId` → `IllegalArgumentException`,
  "Execution not found: ...", no emit).
- node-not-found case (known `executionId`, unknown `nodeId` → `IllegalArgumentException`,
  "Node not found: ...", no emit).

Existing happy-path and emit-error tests are unaffected.

**Controller (`WorkflowControllerTest`)** — for each of the three new endpoints, add:
- success case (200, `ApiResponse.success` with `WorkflowStartResponse(executionId)`).
- execution-not-found case (404).
- node-not-found case (404).

Following the exact structure of the existing `pauseNode`/`skipNode` controller tests.

## Error handling

No new error paths — reuses `executeControlSignal`'s existing `IllegalArgumentException`
→ 404 translation, which already distinguishes "Node not found" vs "Execution not found"
by message prefix.

## Backward compatibility

Additive only. `DefaultControlBusGateway`'s new validation is a behavior *fix*, not a
breaking change: today, calling these methods for a nonexistent execution/node throws
later (or is a no-op) inside the control bus dispatch machinery — likely a silent no-op
rather than a clean error. After this change, callers get an immediate, well-formed
`IllegalArgumentException`/404, consistent with the other node-control operations.
