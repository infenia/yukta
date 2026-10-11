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
- `yukta.api-key` (optional) guards `/api/**`; the server binds to loopback unless `YUKTA_SERVER_ADDRESS` is set.
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
