# web — Review, Improvements & Optimisations

> Scope: `web/src/main` (44 files, ≈3.9k lines) reviewed on 2026-10-09 against `main` @ `8d3161b5`.
> Companion: [`web-module-guide.html`](web-module-guide.html) is the interactive guide, including an endpoint map.

## 1. Verdict

`web` is Yukta's REST and SSE boundary: six controllers, 25 DTOs, three MapStruct mappers, one
`@RestControllerAdvice`, and two `WebFilter`s. Controllers stay reactive end to end, and the
OpenAPI annotations are thorough.

As a trust boundary, though, it's weak:

- **There's no authentication on any endpoint.** Anyone who can reach the port can submit a
  workflow (`POST /api/sessions`). Combined with plugin-api A1 (SpEL) and the process-executor
  plugin, that means code execution on the host.
- **Error handling is done in each controller instead of the global advice.** `start`,
  `stopWorkflow` and `stopExecution` turn *every* error into **404 "Not found"** with the raw
  exception message. Restart and the control endpoints do the same for every
  `IllegalArgumentException`, which core also uses for validation errors.
- **Malformed input is reported as "Unknown field: 'unknown'".** That happens for every kind of
  body or parameter problem.
- **About 60% of `WorkflowController` (954 lines) is repeated** logging and error-mapping
  boilerplate.

### Scorecard

| Area | Score | Notes |
|---|---|---|
| API design | 2.5/5 | Three URL shapes for execution operations. `sessionId` isn't checked on the status, stream and log endpoints |
| Security | 1/5 | No auth. Exception messages leak. Unvalidated admin command endpoint |
| Error handling | 2/5 | Errors map to 404. Wrong 400 messages |
| Streaming (SSE) | 3/5 | Works, but logs can be lost between the history read and the live feed. No heartbeat |
| Simplicity | 2/5 | Repeated boilerplate. Wrapper DTOs around a single list |
| Tests | 4/5 | 44 test classes |

---

## 2. Findings

Severity: **P0** fix now · **P1** fix next · **P2** should fix · **P3** clean-up. Effort: S < ½ day, M ≈ 1–2 days, L > 2 days.

### 2.1 Security

#### W1 · P0 · No authentication or authorisation on any endpoint — Effort M
- **Where:** There's no `spring-boot-starter-security` and no `SecurityWebFilterChain`.
  `POST /api/sessions` accepts workflow definitions. The `/api/workflow/**` control endpoints and
  `/api/control/**/command` are open.
- **Impact:** Anyone on the network can start, stop or restart workflows and submit new ones.
  New workflows can run code (plugin-api A1 SpEL, the process-executor plugin).
- **Fix (in order of effort):**
  1. Bind to `127.0.0.1` by default (`server.address`) and document how to opt in to remote access.
  2. Add a single API-key `WebFilter` (`Authorization: Bearer <key>` from config) for `/api/**`.
  3. Later: Spring Security with OAuth2 resource server, and roles for read, operate and author.

#### W2 · P1 · Exception messages leak to clients — Effort S
- **Where:** `GlobalExceptionHandler.handleGenericException` returns
  `"An unexpected error occurred: " + exception.getMessage()`. `handleIllegalState` returns the raw
  message with a 500. The controller `onErrorResume` blocks copy `e.getMessage()` into `FieldError`.
- **Impact:** Internal details reach the client: class names, file paths, SQL-like text.
- **Fix:** For 5xx responses, return a fixed message plus the `X-Request-ID`, and log the cause.
  Only `ResourceNotFoundException` and `ValidationException` messages are client-safe.

#### W3 · P2 · The admin command endpoint takes an arbitrary map — Effort S
- **Where:** `ControlBusController.sendCommand` wraps `@RequestBody Map<String, Object>` into a
  control message and returns the plugin's raw `Message<?>`.
- **Fix:** Use a typed request record with `@Valid`, put it behind auth (W1), and return a DTO
  instead of the internal envelope.

### 2.2 Correctness

#### W4 · P1 · Controller error mapping turns failures into 404 — Effort M
- **Where:** `startWorkflow`, `stopWorkflow` and `stopExecution` use
  `onErrorResume(e -> 404 "…not found", e.getMessage())` for **any** error. `restartWorkflow`,
  `restartFromNode` and `executeControlSignal` do the same for any `IllegalArgumentException`,
  which core also throws for invalid input.
- **Impact:** Validation errors, plugin failures, timeouts and bugs all look like "not found". The
  UI and clients can't tell what happened.
- **Fix:** Delete these `onErrorResume` blocks. In core, throw `ResourceNotFoundException` for
  "not found" cases. `GlobalExceptionHandler` then maps 404 / 400 / 500 consistently. This also
  removes about 300 lines.

#### W5 · P3 · The error handler branches on message text — Effort S
- **Where:** `WorkflowController.executeControlSignal` does
  `e.getMessage().startsWith("Node not found")` to choose the response.
- **Impact:** Renaming a message in core silently changes the HTTP response. An
  `IllegalArgumentException` with a null message would throw an NPE inside the handler.
- **Fix:** Covered by W4. Never branch on message text: use exception types.

#### W6 · P1 · Every malformed request is reported as "Unknown field: 'unknown'" — Effort S
- **Where:** `GlobalExceptionHandler.handleServerWebInputException` always builds an
  "Unknown field" error, even when there's no `UnrecognizedPropertyException`.
- **Impact:** Bad JSON, a wrong type, a missing body or a bad path-variable type all return the same
  misleading message.
- **Fix:** Only produce "Unknown field" when `unrecognized != null`. Otherwise use
  `exception.getReason()` or delegate to `super`.

#### W7 · P2 · Read endpoints don't check that the execution belongs to the session — Effort S
- **Where:** The control endpoints (`executeControlSignal`) do check ownership. But
  `/workflow/{sessionId}/status/{executionId}`, `/status/{executionId}/stream` and the log streams
  look the execution up by `executionId` only.
- **Impact:** Session A's URL can read session B's status and logs. Once W1 adds per-session
  permissions, this is an IDOR.
- **Fix:** Reuse the `executeControlSignal` ownership check, ideally moved into one helper or
  filter, or drop `sessionId` from those paths (see W10).

#### W8 · P2 · Log SSE can lose or duplicate lines at the history/live seam — Effort M
- **Where:** `LogManagementController.streamExecutionLogs` uses
  `historicalLogs.concatWith(liveLogs)`. The live sink is `directBestEffort` with no replay.
- **Impact:** Lines written after the historical read finishes, but before the live subscription
  starts, are lost. Lines written during the read can appear twice.
- **Fix:** Subscribe to live first into a bounded replay (`liveLogs.replay(256).autoConnect()`),
  read history, then emit live entries with `seq > lastHistoricalSeq`. That needs a sequence number
  on `PluginLogEntry`.

### 2.3 API design and performance

| ID | Finding | Fix | Effort |
|---|---|---|---|
| W9 | SSE streams have no heartbeat or `id:`. Idle proxies close them, and reconnects replay the full history | Merge in `Flux.interval(15s).map(_ -> ServerSentEvent.comment("ping"))`. Set `id` from a sequence number and honour `Last-Event-ID` | S |
| W10 | Three URL shapes for execution operations: `/workflow/executions/{id}/stop`, `/workflow/{sessionId}/{executionId}/pause`, `/workflow/{sessionId}/status/{executionId}` | Pick one, for example `/api/executions/{id}` with `/stop`, `/pause`, `/nodes/{nodeId}/pause` and `/status/stream`. Keep the old paths as deprecated aliases for one release | M |
| W11 | List endpoints have no pagination: `/api/sessions`, `/summaries`, `/workflow/{sid}/history`. `/summaries` reads every session config file | Add `?limit&cursor`. Cache the summaries or derive them from the in-memory index | M |
| W12 | Per-endpoint INFO logs ("reached", "succeeded", "response sent") plus `RequestLoggingFilter` mean every request is logged 3–4 times | Keep the filter, at INFO with status and duration. Delete the per-endpoint logs | S |
| W13 | `RequestTracingFilter` sets `X-Request-ID` but doesn't put it into the Reactor context or MDC, so logs can't be correlated. It also echoes client values without validating them | `contextWrite(Context.of("requestId", id))` plus Micrometer context propagation. Accept only `[A-Za-z0-9-]{1,64}` | S |
| W14 | `ApiResponse` repeats the HTTP status in the body, and `ErrorExample` exists only for Swagger | Consider RFC 9457 `ProblemDetail` for errors (built into `ResponseEntityExceptionHandler`) and plain bodies for success | M |
| W15 | Wrapper DTOs around a single list: `SessionList`, `SessionListItems`, `WorkflowSummaries`, `WorkflowExecutions` | Return `ApiResponse<List<…>>` directly | S |
| W16 | `WorkflowController` is 954 lines, about 60% boilerplate | After W4 and W12, split it into `ExecutionController` (lifecycle) and `ExecutionControlController` (pause/step/skip) | S |

### 2.4 Tests

| ID | Missing test | Catches |
|---|---|---|
| W17 | `WebTestClient` cases: malformed JSON, wrong type, a null-message exception from the gateway, a 500 that doesn't leak the message, cross-session execution ID | W2, W4, W5, W6, W7 |

---

## 3. Roadmap

| Phase | Items | Outcome |
|---|---|---|
| **1. Lock the door** (1 day) | W1 (localhost + API key), W2, W3 | No anonymous remote code execution |
| **2. Honest errors** (1 day) | W4, W5, W6, W17 | Correct status codes, no leaks, about 300 fewer lines |
| **3. Streams** (1 day) | W8, W9 | No lost log lines, and connections survive proxies |
| **4. API shape** (2 days) | W7, W10, W11, W14, W15, W16 | Consistent, paginated, smaller |
| **5. Observability** (½ day) | W12, W13 | Request-correlated logs at a fraction of the volume |
