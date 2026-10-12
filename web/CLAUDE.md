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
- `yukta.api-key` (optional) guards `/api/**` with one key that may do everything; the server binds to loopback unless `YUKTA_SERVER_ADDRESS` is set. `yukta.api-keys[i].{name,key,role}` adds named keys with a role: `VIEWER` (GET only), `OPERATOR` (also POST/DELETE under `/api/executions/` and `/api/workflow/`), `ADMIN` (everything, including sessions/workflows, scheduler limits and raw node commands). A role that does not allow a request gets 403; with named keys the control audit records the actor `key:<name>`. MCP (`yukta.mcp.api-key`) has no roles yet.
- **MapStruct**: Used for DTO mapping (`AppConfigMapper`).

## Key Files
- **Controllers**: `src/main/java/com/infenia/yukta/controller/`
- **Mappers**: `src/main/java/com/infenia/yukta/mapper/`
- **Module guide (interactive, with the resolved review findings)**: `docs/web-module-guide.html`

## Imports
@.claude/rules/coding-standards.md
@.claude/rules/git-workflow.md

## Testing speed
Follow the tiered "Test Execution Policy (speed)" in the root `CLAUDE.md`: run `--tests` for the classes you changed first; full `:web:test` only when many files changed.

- `GET /api/sessions/{s}/workflows/{w}/versions` lists the saved versions of a workflow (newest 20, newest first); `POST .../rollback?version=<id>` makes one current again (saved as a new version, workflow prepared again).
