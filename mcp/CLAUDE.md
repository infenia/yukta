# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited

# MCP Module CLAUDE.md

## Overview
MCP server implementation and tool providers. Mirrors the functionality of the `web` REST
controllers as an agent-friendly MCP tool surface backed directly by `core` services
(no dependency on `:web`).

## Build & Development Commands
```bash
# Run MCP tests
./gradlew :mcp:test
```

## Tools (`AppMcpTools`)
- **Sessions**: `list_sessions`, `get_session_details`, `create_session`,
  `get_session_creation_instructions`
- **Workflows**: `get_workflow_details`, `start_workflow`, `get_workflow_status`
  (WorkflowProgress snapshot), `get_workflow_history`, `get_execution_logs`
  (real logs from `PluginLogStore`, regex filter + tail)
- **Control**: `control_workflow` (PAUSE/RESUME/STOP/STOP_ALL/RESTART/RESTART_FROM_NODE),
  `control_node` (PAUSE/RESUME/STOP/SKIP/UNSKIP/STEP/STEP_ENABLE/STEP_DISABLE)
- **Plugins**: `list_plugins`, `get_plugin_details`, `get_plugin_creation_guide`
- **Monitoring**: `get_control_bus_status`

## Key Files
- `src/main/java/com/infenia/yukta/mcp/AppMcpTools.java`: `@McpTool` facade.
- `src/main/java/com/infenia/yukta/mcp/AppMcpPrompts.java`: `@McpPrompt` templates.
- `src/main/java/com/infenia/yukta/mcp/AppMcpResources.java`: `@McpResource` providers.
- `src/main/java/com/infenia/yukta/mcp/provider/`: Backing implementations (workflow
  execution/control, logs, sessions, plugins, system health) delegating to core services
  (`ControlBusGateway`, `WorkflowService`, `SessionService`, `PluginRegistry`,
  `PluginLogStore`).
- `src/main/java/com/infenia/yukta/mcp/dto/`: MCP-owned response records (plain records,
  no swagger annotations). Core records (`WorkflowProgress`, `WorkflowExecutionSummary`,
  `WorkflowDefinition`) are returned directly where already clean.

## Conventions & Gotchas
- Tool params use `@McpToolParam(required, description)`; `@McpArg` is only for
  `@McpPrompt` arguments.
- The server runs `type: ASYNC` + `protocol: STATELESS` (configured in
  `boot/src/main/resources/application*.yaml`). Tools return `Mono`; blocking core calls
  are wrapped in `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())`.
- **List-shaped tools MUST return `Mono<WrapperRecord>`** (e.g. `SessionList`,
  `WorkflowHistory`, `PluginList`), never `Flux<T>` or bare `Mono<List<T>>`: the async MCP
  tool callback truncates a `Flux` result to its first element (`fluxResult.next()`), and
  `generateOutputSchema = true` on a `Mono<List<T>>` return type crashes server startup
  (Spring AI 2.0.0 passes null to `ClassUtils.isPrimitiveOrWrapper` for parameterized
  types).
- Errors surface as `Mono.error(new IllegalArgumentException(...))` with agent-actionable
  messages; the framework converts them to `isError` tool results.
- Every executionId-scoped tool verifies session ownership via
  `ControlBusGateway.getCurrentProgress`; a missing execution and a foreign session yield
  the identical "Execution not found" error (mirrors `WorkflowController.executeControlSignal`).

## Tech Stack
- **Spring AI MCP Server** (WebFlux, Spring AI 2.0)

## Imports
@.claude/rules/coding-standards.md
@.claude/rules/git-workflow.md
