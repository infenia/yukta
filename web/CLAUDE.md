# Web Module CLAUDE.md

## Overview
Web layer with REST controllers and UI support.

## Build & Development Commands
```bash
# Run web tests
./gradlew :web:test
```

## Patterns
- Controllers return `Mono<ApiResponse<T>>` (or a `Flux` of `ServerSentEvent`). They never catch errors: core throws `NotFoundException`, `GlobalExceptionHandler` maps the status.
- Execution routes live at `/api/executions/{id}/…`; the `/api/workflow/…` paths are deprecated aliases. Use `ExecutionAccess` for session-ownership checks, `Pagination` for lists and `SseStreams` for SSE.
- `GET /api/plugins/{type}` includes `configSchema`; `POST /api/sessions/validate` dry-runs a draft workflow with `WorkflowValidator` (always 200, `valid=false` plus the first problem) and returns each node's output ports; `GET /api/sessions/{id}/config` returns the full config in the shape `POST /api/sessions` accepts.
- `GET /api/supervisors` lists the running supervisors and control-plane plugins (`SupervisorHost.status()`: id, kind, type, order, dry-run, and applied/rejected/recorded/dropped command counts since startup); the UI shows it on the Control page.
- `yukta.api-key` (optional) guards `/api/**` and the actuator (except `/actuator/health`) with one key that may do everything; the server binds to loopback unless `YUKTA_SERVER_ADDRESS` is set, and refuses to start on another address without a key (`ExposureGuard` in boot). `yukta.api-keys[i].{name,key,role,sessions}` adds named keys with a role: `VIEWER` (GET only), `OPERATOR` (also POST/DELETE under `/api/executions/` and `/api/workflow/`), `ADMIN` (everything, including sessions/workflows, scheduler limits, raw node commands and the actuator). A key is plain text or `sha256:<hex>`; several keys at once allow rotation. A key with `sessions` reaches only `/api/sessions/{s}/**`, `/api/workflow/{s}/**` and `/api/executions/{id}/**` (runs in those sessions only). A role or session that does not allow a request gets 403; with named keys the control audit records the actor `key:<name>`. The path is matched after decoding; `;`, encoded `/ \ . %`, `//` and dot segments give 400 (`RequestPaths`). `HostOriginFilter` (only without a key) answers 403 to a Host that is not loopback / `yukta.allowed-hosts`, and to a non-GET whose Origin is not the Host / `yukta.allowed-origins`. MCP (`McpEndpointFilter`) uses the same keys plus `yukta.mcp.api-key`, and checks each `tools/call` against the key: `VIEWER` reads, `OPERATOR` also starts and controls, `ADMIN` does everything (`McpToolAccess`); a session-limited key needs a `sessionId` of its own.
- `GET /api/workflow/{s}/history` takes `workflowId`, `status`, `text` (in the execution id), `since` and `until` (start time, ISO date-time) before it pages (`HistoryFilter`).
- `GET /api/executions?sessionId=&limit=&workflowId=&status=&text=&since=&until=` searches the runs of all sessions, newest first (`ExecutionSearchController`; each session's history is bounded by `yukta.execution-log.retained-runs`; a key limited to sessions cannot use it).
- `GET /api/control/audit?limit=&executionId=` lists the newest control commands from the execution log (applied or refused, with the actor); the UI shows it on the Control page.
- `GET /api/events/stream` envelopes carry a `severity` (`EventSeverity`: `ERROR` for a failed node/run or an error line, `WARN` for a rejected command, cancelled node/run or warning line, else `INFO`); `?severity=WARN` sends that and above. Log lines are rated by keyword (`ponytail:` in `EventSeverity`), so a plugin that reports a level should replace it.
- **MapStruct**: Used for DTO mapping (`AppConfigMapper`).

## Key Files
- **Controllers**: `src/main/java/com/infenia/yukta/controller/`
- **Mappers**: `src/main/java/com/infenia/yukta/mapper/`
- **Module guide**: `../docs/contributing/modules/web.mdx` (module guide, published in the docs site); open risks and review findings: `../docs/internal/risks.mdx` and `../docs/internal/improvements.mdx` (section for this module)

## Imports
@.claude/rules/coding-standards.md
@.claude/rules/git-workflow.md

## Testing speed
Follow the tiered "Test Execution Policy (speed)" in the root `CLAUDE.md`: run `--tests` for the classes you changed first; full `:web:test` only when many files changed.

- `GET /api/sessions/{s}/workflows/{w}/versions` lists the saved versions of a workflow (newest 20, newest first); `POST .../rollback?version=<id>` makes one current again (saved as a new version, workflow prepared again).
