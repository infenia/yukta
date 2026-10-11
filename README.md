<div style="text-align: center;">
  <img src="ui/public/logo.svg" alt="Yukta" width="120" height="120" style="border-radius: 64px; padding: 4px; background-color: #fff;" />
  <h1>Yukta</h1>
  <p><strong>A reactive, DAG-based workflow orchestration server.</strong> Define workflows once as JSON, run them via REST, a web UI, or MCP.</p>
</div>

[![License: Apache 2.0](https://img.shields.io/badge/license-Apache%202.0-blue?style=flat-square)](LICENSE)
[![Java 25](https://img.shields.io/badge/java-25-orange?style=flat-square)](https://www.oracle.com/java/)
[![Spring Boot 4.1.1](https://img.shields.io/badge/spring--boot-4.1.1-6db33f?style=flat-square)](https://spring.io/projects/spring-boot)
[![Gradle 9.8](https://img.shields.io/badge/gradle-9.8-02303a?style=flat-square)](https://gradle.org/)
[![MCP Native](https://img.shields.io/badge/MCP-native-blueviolet?style=flat-square)](https://modelcontextprotocol.io/)
[![GraalVM](https://img.shields.io/badge/GraalVM-native%20image-red?style=flat-square)](https://www.graalvm.org/)
[![Status: Pre-Beta](https://img.shields.io/badge/status-pre--beta-yellow?style=flat-square)](#project-status)

[![CI](https://github.com/infenia/yukta/actions/workflows/ci.yml/badge.svg)](https://github.com/infenia/yukta/actions/workflows/ci.yml)
[![CodeQL](https://github.com/infenia/yukta/actions/workflows/codeql.yml/badge.svg)](https://github.com/infenia/yukta/actions/workflows/codeql.yml)
[![Security Scan](https://github.com/infenia/yukta/actions/workflows/security.yml/badge.svg)](https://github.com/infenia/yukta/actions/workflows/security.yml)
[![OpenSSF Scorecard](https://api.scorecard.dev/projects/github.com/infenia/yukta/badge)](https://scorecard.dev/viewer/?uri=github.com/infenia/yukta)
[![codecov](https://codecov.io/gh/infenia/yukta/branch/main/graph/badge.svg)](https://codecov.io/gh/infenia/yukta)
[![REUSE status](https://api.reuse.software/badge/github.com/infenia/yukta)](https://api.reuse.software/info/github.com/infenia/yukta)

---

## Project Status

> **Yukta is pre-beta (`0.0.1-SNAPSHOT`).** The engine, plugins, REST API, and MCP server all run and are exercised by the test suite, but there are no tagged releases or downloadable binaries yet, and no changelog. Expect breaking changes to the JSON workflow schema and APIs before v0.1.0. This section is the source of truth for "what actually works today" — update it whenever a module's status changes, rather than scattering status claims through the rest of the doc.

| Area                                                                                       | Status                        | Notes                                                                                          |
|--------------------------------------------------------------------------------------------|-------------------------------|------------------------------------------------------------------------------------------------|
| DAG orchestration engine (`core`)                                                          | Working                       | Reactive compiler/validator/executor; requires each workflow to have at least one trigger node |
| REST API (`web`)                                                                           | Working                       | 9 controllers, 39 operations; see [REST API](#rest-api)                                        |
| MCP server (`mcp`)                                                                         | Working                       | 16 tools, 2 prompts, 3 resources over `/mcp` (always on, no flag needed)                       |
| Built-in plugins (`plugins`)                                                               | Working                       | 25 plugins across trigger/processor/terminal; see [Plugins](#plugins)                          |
| Rule supervisor (`plugins/supervisors`)                                                    | Working, opt-in               | Declarative rules over the control bus; enabled by `yukta.supervisor.rules.file`               |
| Web UI (`ui`)                                                                              | Working                       | React 19 + Vite + Tailwind CSS single-page app served at `/`                                   |
| Native image (GraalVM)                                                                     | Configured, not yet published | `./gradlew nativeCompile` builds a `yukta` binary; no released artifacts                       |
| Go CLI (`cli`)                                                                             | In progress                   | See `cli/CLAUDE.md`                                                                            |
| CI                                                                                         | Working                       | `.github/workflows/ci.yml`, runs on pushes and PRs to `main` plus a nightly run                |
| Tagged releases / downloads                                                                | Not yet                       | `release.yml` publishes on `v*` tags, but no tags, CHANGELOG.md, or GitHub Releases exist yet |
| User-facing docs (getting-started, architecture, api-reference, plugin-development guides) | Not yet                       | `docs/` currently only has internal notes; use module-level `CLAUDE.md` files in the meantime  |

**Path to beta** (rough, update as items land):
- [ ] Publish a first tagged release with native-image binaries
- [ ] Add CHANGELOG.md
- [ ] Write the user-facing docs referenced above
- [ ] Stabilize the JSON workflow schema and REST/MCP contracts

---

## What Yukta Does

Define a workflow once as a DAG of nodes and edges, then run it the same way from a script, a CI pipeline, or an AI agent:

```json
{
  "sessionId": "demo",
  "name": "Quick demo",
  "description": "Quick demo",
  "initiator": "me",
  "projectPath": "/tmp/demo",
  "workflows": {
    "hello": {
      "workflowId": "hello",
      "description": "Echo a greeting",
      "nodes": [
        { "nodeId": "start", "type": "ONE_SHOT", "config": {} },
        { "nodeId": "echo", "type": "PROCESS_EXECUTOR", "config": { "command": ["echo", "Hello from Yukta!"] } },
        { "nodeId": "done", "type": "CONSOLE_TERMINAL", "config": {} }
      ],
      "edges": [
        { "source": "start", "target": "echo" },
        { "source": "echo", "target": "done" }
      ]
    }
  }
}
```

Every workflow needs at least one **trigger** node (an entry point implementing `TriggerPlugin`), feeding into **processor** nodes, and at least one **terminal** node (a `TerminalPlugin`) to end a branch. The orchestrator validates the DAG (reachability, cycles, entry points) before it will run.

---

## Architecture

```
┌─────────────────────────────────────┐
│ Transport Layer                     │
│ REST API (web) + MCP server (mcp)   │
│ + Web UI (ui)                       │
└──────────────┬──────────────────────┘
               ↓
┌─────────────────────────────────────┐
│ Orchestration Layer (core)          │
│ DAG validator, compiler, executor   │
│ Reactive (Project Reactor), no      │
│ blocking, virtual threads for I/O   │
└──────────────┬──────────────────────┘
               ↓
┌─────────────────────────────────────┐
│ Plugin Layer (plugin-api, plugins)  │
│ TriggerPlugin / ProcessorPlugin /   │
│ TerminalPlugin implementations,     │
│ ExecutionSupervisor (control bus)   │
└─────────────────────────────────────┘
```

Each layer is independent — plugins are Spring `@Component` beans implementing an interface from `plugin-api`, so custom plugins don't require touching `core` or `web`.

---

## Plugins

25 built-in plugins across 15 Gradle modules under `plugins/` (`processors`, `triggers`, `terminals`, `supervisors`). Plugin type strings are used as-is from the code (case and separator are inconsistent between plugins — that's the current state, not a typo).

**Triggers** (entry points — start a workflow)

| Type              | Class                  | Description                                                                |
|-------------------|------------------------|----------------------------------------------------------------------------|
| `ONE_SHOT`        | `OneShotTrigger`       | Emits a single empty message to start a workflow with no input             |
| `CONSTANT_SOURCE` | `ConfigVariableSource` | Emits a message with predefined variables at workflow startup              |
| `api-trigger`     | `ApiTriggerPlugin`     | Passes the payload received from an API trigger directly into the workflow |
| `SCHEDULE_TRIGGER` | `ScheduleTrigger`     | Emits a message repeatedly on a fixed interval or a cron schedule          |

**Processors** (transform / route messages)

| Type               | Class                    | Description                                                                              |
|--------------------|--------------------------|------------------------------------------------------------------------------------------|
| `FILE_READ`        | `FileReadPlugin`         | Reads a UTF-8 text file from a sandbox directory                                         |
| `FILE_WRITE`       | `FileWritePlugin`        | Writes or appends text to a file in a sandbox directory; passes the message through      |
| `HTTP_REQUEST`     | `HttpRequestPlugin`      | Performs an HTTP request per message and emits status, success, headers, and body       |
| `SQL_QUERY`        | `SqlQueryPlugin`         | Runs a parameterized SQL statement over JDBC for each message                            |
| `LLM_CALL`         | `LlmCallPlugin`          | Calls an LLM (OpenAI-compatible, Anthropic, Ollama) and extracts `<agent_action>` blocks |
| `SESSION_MEMORY`   | `SessionMemoryPlugin`    | Per-session chat memory: `load` attaches history, `append` records the assistant turn   |
| `PROCESS_EXECUTOR` | `ProcessExecutorPlugin`  | Runs an external OS process and emits a configurable result (exit code, output)          |
| `FILTER`           | `FilterProcessor`        | Evaluates a boolean predicate; matching messages pass through                            |
| `VALIDATE`         | `ValidateProcessor`      | Checks required fields and types; failures are routed to the `invalid` port              |
| `BRANCH`           | `BranchProcessor`        | Routes messages to different ports based on exact-match selectors or conditions          |
| `SPLITTER`         | `SplitterProcessor`      | Splits a composite message into individual items                                         |
| `RECIPIENT_LIST`   | `RecipientListProcessor` | Propagates a single message to a dynamically specified set of recipients                 |
| `CONTENT-FILTER`   | `ContentFilterProcessor` | Removes unimportant, redundant, or sensitive data items                                  |
| `MAPPER`           | `MapperProcessor`        | Transforms payloads via PROJECTION (SpEL), TEMPLATE (Handlebars), or SCRIPT (GraalVM JS) |
| `LOOP_PREDICATE`   | `LoopPredicateProcessor` | Repeats a target plugin until an exit condition is met; emits the final result           |
| `LOOP_STREAM`      | `LoopStreamProcessor`    | Repeats a target plugin and flattens all produced messages into one stream               |
| `AGGREGATE`        | `AggregateProcessor`     | Batches incoming messages into one message holding the list of their payloads           |
| `DELAY`            | `DelayProcessor`         | Delays each message by a fixed time, releasing messages one at a time, in order          |
| `KV_STORE`         | `KvStoreProcessor`       | Shared in-memory key-value store for state across workflow runs                          |

**Terminals** (end a workflow branch)

| Type               | Class                   | Description                                                      |
|--------------------|-------------------------|------------------------------------------------------------------|
| `CONSOLE_TERMINAL` | `ConsoleTerminalPlugin` | Logs the message payload to console/logger                       |
| `NOTIFY_TERMINAL`  | `NotifyTerminal`        | Sends each message as a JSON webhook notification (Slack-compatible) |

**Supervisors** (observe and gate control-bus commands)

`ExecutionSupervisor` is an SPI in `plugin-api`. `plugins/supervisors/rule-supervisor` ships a declarative implementation driven by a JSON rules file, enabled by setting `yukta.supervisor.rules.file`.

Custom plugins implement `TriggerPlugin`, `ProcessorPlugin`, or `TerminalPlugin` from `plugin-api` and register as a Spring bean — no changes to `core` needed. See `plugin-api/CLAUDE.md` and `plugins/CLAUDE.md` for the interfaces and conventions, or ask the MCP server's `get_plugin_creation_guide` tool for a template.

---

## MCP Server

Yukta runs an MCP server (Spring AI MCP, WebFlux) on the same process, always on at `/mcp` (`/sse` still works as an alias; protocol `STATELESS`, type `ASYNC` — no separate flag or profile required).

**16 tools**: `list_sessions`, `get_session_details`, `create_session`, `get_session_creation_instructions`, `get_workflow_details`, `start_workflow`, `get_workflow_status`, `get_workflow_history`, `control_workflow`, `control_node`, `set_node_breakpoint`, `get_execution_logs`, `list_plugins`, `get_plugin_details`, `get_plugin_creation_guide`, `get_control_bus_status`

**2 prompts**: `debug-workflow`, `create-session-config`
**3 resources**: `yukta://overview`, `yukta://architecture`, `yukta://sessions/{sessionId}/executions/{executionId}/logs`

Point any MCP-compatible client at `http://localhost:8080/mcp` (streamable HTTP transport, `STATELESS`/`ASYNC` protocol). Set `yukta.mcp.api-key` to require `Authorization: Bearer <key>` (or `X-API-Key`) on that endpoint; it is open by default, like the REST API.

---

## REST API

Base path `/api`, 9 controllers, 39 operations. Full interactive docs at `http://localhost:8080/swagger-ui.html` once running.

| Controller                   | Base path                                   | Handles                                                                                  |
|------------------------------|---------------------------------------------|------------------------------------------------------------------------------------------|
| `SessionConfigController`    | `/api/sessions`                             | Apply (create/update) session config, list sessions (paged), session summaries, read workflow definitions |
| `WorkflowDetailsController`  | `/api/sessions/{sessionId}/workflows/{workflowId}` | Workflow graph and per-workflow executions                                        |
| `WorkflowController`         | `/api/workflow`                             | Start a workflow, stop its runs, session execution history                               |
| `ExecutionController`        | `/api/executions`                           | Execution status (poll or SSE stream), stop, restart (optionally from a node)            |
| `ExecutionControlController` | `/api/executions`                           | Pause/resume an execution; per-node stop, skip, retry, step, breakpoints; priority; scheduler limits |
| `LogManagementController`    | `/api/sessions/{sessionId}/executions/{executionId}/logs` | Execution log streams (SSE)                                        |
| `ControlBusController`       | `/api/control`                              | Live node introspection, heartbeats, and node commands                                   |
| `EventsController`           | `/api/events`                               | Live event stream (SSE)                                                                  |
| `PluginController`           | `/api/plugins`                              | List plugins and get plugin details                                                      |

The older `/api/workflow/{sid}/{eid}/…` execution paths still work as deprecated aliases. List endpoints take `?limit=&cursor=` and announce the next page in the `X-Next-Cursor` header.

**Access control.** The server listens on `127.0.0.1` by default. To expose it, set `YUKTA_SERVER_ADDRESS=0.0.0.0` and `yukta.api-key`; clients then send `Authorization: Bearer <key>` (or `X-API-Key`) on every `/api/**` call.

---

## Web UI

A React 19 single-page app built with Vite and Tailwind CSS 4, served by the Spring Boot app at the root path `/`. Routes include `/` (sessions), `/sessions/:sessionId/workflow/:workflowId` (live DAG visualization), `/history` (execution history), and `/control` (control console). See `ui/CLAUDE.md`.

---

## Getting Started

There are no tagged releases yet, so the only way to run Yukta today is from source.

```bash
git clone https://github.com/infenia/yukta.git
cd yukta

# Run (Spring Boot, JVM)
./gradlew bootRun
# → http://localhost:8080/swagger-ui.html
# → http://localhost:8080/

# Optional: build a GraalVM native image (requires a GraalVM JDK)
./gradlew nativeCompile
# → boot/build/native/nativeCompile/yukta
```

### Try it

```bash
# 1. Create a session with a workflow
curl -X POST http://localhost:8080/api/sessions \
  -H "Content-Type: application/json" \
  -d '{
    "sessionId": "demo",
    "name": "Quick demo",
    "description": "Quick demo",
    "initiator": "me",
    "projectPath": "/tmp/demo",
    "workflows": {
      "hello": {
        "workflowId": "hello",
        "description": "Echo a greeting",
        "nodes": [
          { "nodeId": "start", "type": "ONE_SHOT", "config": {} },
          { "nodeId": "echo", "type": "PROCESS_EXECUTOR", "config": { "command": ["echo", "Hello from Yukta!"] } },
          { "nodeId": "done", "type": "CONSOLE_TERMINAL", "config": {} }
        ],
        "edges": [
          { "source": "start", "target": "echo" },
          { "source": "echo", "target": "done" }
        ]
      }
    }
  }'

# 2. Start it
curl -X POST http://localhost:8080/api/workflow/start \
  -H "Content-Type: application/json" \
  -d '{ "sessionId": "demo", "workflowId": "hello" }'
# → { "data": { "executionId": "..." }, ... }

# 3. Check status (or stream live SSE updates from the /status/stream variant)
curl http://localhost:8080/api/executions/{executionId}
curl -N http://localhost:8080/api/executions/{executionId}/status/stream
```

### Contributing / building custom plugins

```bash
./gradlew check          # tests + Checkstyle + PMD + SpotBugs (+ OpenGrep if installed)
./gradlew spotlessApply  # format before committing
```

See the root [`CLAUDE.md`](CLAUDE.md) and each module's own `CLAUDE.md` for module-specific build commands and architecture notes, and [`CONTRIBUTING.md`](CONTRIBUTING.md) for the PR workflow.

---

## Tech Stack

- **Java 25** (Gradle toolchain)
- **Spring Boot 4.1.1** (WebFlux)
- **Gradle 9.8**
- **Project Reactor** (non-blocking, `Mono`/`Flux`)
- **GraalVM** (native image target)
- **Spring AI MCP Server**
- **React 19 + Vite + Tailwind CSS 4 + React Router** (web UI)
- **Go + Cobra** (CLI, `cli` module)

## Repository Layout

Multi-module Gradle monorepo. Each module has its own `CLAUDE.md` with module-specific commands and notes.

- **`boot`** — Spring Boot entry point, GraalVM native image config
- **`build-logic`** — Gradle build conventions (Java, quality gates, JaCoCo)
- **`cli`** — Go CLI for remote server interaction
- **`core`** — DAG orchestration engine, plugin registry
- **`mcp`** — MCP server implementation
- **`messaging`** — Shared messaging abstractions
- **`plugin-api`** — Plugin interfaces (`TriggerPlugin`, `ProcessorPlugin`, `TerminalPlugin`, `ExecutionSupervisor`) and store abstractions
- **`plugins`** — Plugin implementations (`processors`, `triggers`, `terminals`, `supervisors`)
- **`ui`** — React/Vite/Tailwind frontend
- **`web`** — REST controllers and DTO mapping

---

## Security & License

- Input validation at API boundaries (session IDs, project paths, workflow schema)
- Apache 2.0 licensed — see [LICENSE](LICENSE)
- Report vulnerabilities per [SECURITY.md](SECURITY.md)

---

## Made With ❤️

Developed by **[Infenia Private Limited](https://infenia.com)**.
Creator: Arun Cherthedath Somanathan ([arun@infenia.com](mailto:arun@infenia.com))

*Yukta* (युक्त) is Sanskrit for "united" or "joined."
