# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited

<div style="text-align: center;">
  <img src="ui/src/main/resources/static/images/favicon.svg" alt="Yukta" width="120" height="120" style="border-radius: 64px; padding: 4px; background-color: #fff;" />
  <h1>Yukta</h1>
  <p><strong>A reactive, DAG-based workflow orchestration server.</strong> Define workflows once as JSON, run them via REST, a web UI, or MCP.</p>
</div>

[![License: Apache 2.0](https://img.shields.io/badge/license-Apache%202.0-blue?style=flat-square)](LICENSE)
[![Java 25](https://img.shields.io/badge/java-25-orange?style=flat-square)](https://www.oracle.com/java/)
[![Spring Boot 4.1.0](https://img.shields.io/badge/spring--boot-4.1.0-6db33f?style=flat-square)](https://spring.io/projects/spring-boot)
[![Gradle 9.0](https://img.shields.io/badge/gradle-9.0-02303a?style=flat-square)](https://gradle.org/)
[![MCP Native](https://img.shields.io/badge/MCP-native-blueviolet?style=flat-square)](https://modelcontextprotocol.io/)
[![GraalVM](https://img.shields.io/badge/GraalVM-native%20image-red?style=flat-square)](https://www.graalvm.org/)
[![Status: Pre-Beta](https://img.shields.io/badge/status-pre--beta-yellow?style=flat-square)](#project-status)

---

## Project Status

> **Yukta is pre-beta (`0.0.1-SNAPSHOT`).** The engine, plugins, REST API, and MCP server all run and are exercised by the test suite, but there are no tagged releases or downloadable binaries yet, and no changelog. Expect breaking changes to the JSON workflow schema and APIs before v0.1.0. This section is the source of truth for "what actually works today" — update it whenever a module's status changes, rather than scattering status claims through the rest of the doc.

| Area                                                                                       | Status                        | Notes                                                                                          |
|--------------------------------------------------------------------------------------------|-------------------------------|------------------------------------------------------------------------------------------------|
| DAG orchestration engine (`core`)                                                          | Working                       | Reactive compiler/validator/executor; requires each workflow to have at least one trigger node |
| REST API (`web`)                                                                           | Working                       | 5 controllers, 23 endpoints; see [REST API](#rest-api)                                         |
| MCP server (`mcp`)                                                                         | Working                       | 13 tools, 2 prompts, 3 resources over `/sse` (always on, no flag needed)                       |
| Built-in plugins (`plugins`)                                                               | Working                       | 13 plugins across trigger/processor/terminal; see [Plugins](#plugins)                          |
| Web UI (`ui`)                                                                              | Working                       | JTE + Tailwind + Alpine.js dashboard at `/ui`                                                  |
| Native image (GraalVM)                                                                     | Configured, not yet published | `./gradlew nativeCompile` builds a `yukta` binary; no released artifacts                       |
| Go CLI (`cli`)                                                                             | In progress                   | See `cli/CLAUDE.md`                                                                            |
| CI                                                                                         | Working                       | `.github/workflows/ci.yml`, path-filtered per-module builds, nightly run                       |
| Tagged releases / downloads                                                                | Not yet                       | No git tags, no CHANGELOG.md, no GitHub Releases                                               |
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
  "description": "Quick demo",
  "initiator": "me",
  "projectPath": "/tmp/demo",
  "workflows": {
    "hello": {
      "workflowId": "hello",
      "description": "Echo a greeting",
      "nodes": [
        { "nodeId": "start", "type": "MANUAL", "config": {} },
        { "nodeId": "echo", "type": "PROCESS_EXECUTOR", "config": { "command": ["echo", "Hello from Yukta!"] } }
      ],
      "edges": [
        { "source": "start", "target": "echo" }
      ]
    }
  }
}
```

Every workflow needs at least one **trigger** node (an entry point implementing `TriggerPlugin`) feeding into **processor**/**terminal** nodes. The orchestrator validates the DAG (reachability, cycles, entry points) before it will run.

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
│ TriggerPlugin / ProcessorPlugin /    │
│ TerminalPlugin implementations      │
└─────────────────────────────────────┘
```

Each layer is independent — plugins are Spring `@Component` beans implementing an interface from `plugin-api`, so custom plugins don't require touching `core` or `web`.

---

## Plugins

13 built-in plugins across 6 Gradle modules under `plugins/`. Plugin type strings are used as-is from the code (case and separator are inconsistent between plugins — that's the current state, not a typo).

**Triggers** (entry points — start a workflow)

| Type              | Class                  | Description                                                                |
|-------------------|------------------------|----------------------------------------------------------------------------|
| `MANUAL`          | `ManualTrigger`        | Emits a single empty message to start a workflow with no input             |
| `CONSTANT_SOURCE` | `ConfigVariableSource` | Emits a message with predefined/SpEL-evaluated variables at startup        |
| `api-trigger`     | `ApiTriggerPlugin`     | Passes the payload received from an API trigger directly into the workflow |

**Processors** (transform / route messages)

| Type               | Class                    | Description                                                                              |
|--------------------|--------------------------|------------------------------------------------------------------------------------------|
| `FILTER`           | `FilterProcessor`        | Evaluates a boolean predicate; passes or drops/reroutes the message                      |
| `BRANCH`           | `BranchProcessor`        | Routes to different ports via exact-match selectors or SpEL expressions                  |
| `SPLITTER`         | `SplitterProcessor`      | Splits a composite message into individual items (parallel or sequential)                |
| `RECIPIENT_LIST`   | `RecipientListProcessor` | Fans a single message out to a set of static/dynamic/external recipients                 |
| `MAPPER`           | `MapperProcessor`        | Transforms payloads via PROJECTION (SpEL), TEMPLATE (Handlebars), or SCRIPT (GraalVM JS) |
| `CONTENT-FILTER`   | `ContentFilterProcessor` | Removes unimportant/redundant/sensitive fields (include or exclude modes)                |
| `LOOP_PREDICATE`   | `LoopPredicateProcessor` | Repeats a target plugin until an exit condition is met; emits the final result           |
| `LOOP_STREAM`      | `LoopStreamProcessor`    | Repeats a target plugin and flattens all produced messages into one stream               |
| `PROCESS_EXECUTOR` | `ProcessExecutorPlugin`  | Runs an external OS process with reactive streaming output, timeouts, error handling     |

**Terminals** (end a workflow branch)

| Type               | Class                   | Description                                |
|--------------------|-------------------------|--------------------------------------------|
| `CONSOLE_TERMINAL` | `ConsoleTerminalPlugin` | Logs the message payload to console/logger |

Custom plugins implement `TriggerPlugin`, `ProcessorPlugin`, or `TerminalPlugin` from `plugin-api` and register as a Spring bean — no changes to `core` needed. See `plugin-api/CLAUDE.md` and `plugins/CLAUDE.md` for the interfaces and conventions, or ask the MCP server's `get_plugin_creation_guide` tool for a template.

---

## MCP Server

Yukta runs an MCP server (Spring AI MCP, WebFlux) on the same process, always on at `/sse` (protocol `STATELESS`, type `ASYNC` — no separate flag or profile required).

**13 tools**: `list_sessions`, `get_session_details`, `create_session`, `get_session_creation_instructions`, `get_workflow_details`, `trigger_workflow`, `get_workflow_status`, `stream_session_logs`, `get_workflow_execution_logs`, `list_plugins`, `get_plugin_details`, `get_plugin_creation_guide`, `get_control_bus_status`

**2 prompts**: `debug-workflow`, `create-session-config`
**3 resources**: `yukta://overview`, `yukta://architecture`, `yukta://sessions/{sessionId}/logs`

Point any MCP-compatible client at `http://localhost:8080/sse` (SSE transport, `STATELESS`/`ASYNC` protocol). Check your client's own docs for the exact config key names it expects for a remote SSE server.

---

## REST API

Base path `/api`, 5 controllers, 23 endpoints. Full interactive docs at `http://localhost:8080/swagger-ui.html` once running.

| Controller                | Base path       | Handles                                                                   |
|---------------------------|-----------------|---------------------------------------------------------------------------|
| `SessionConfigController` | `/api/sessions` | Create/update session config, list sessions, read workflow definitions    |
| `WorkflowController`      | `/api/workflow` | Start/stop executions, poll or stream (SSE) status, history               |
| `ControlBusController`    | `/api/control`  | Live node/heartbeat introspection, execution progress + log streams (SSE) |
| `PluginController`        | `/api/plugins`  | List plugins and get plugin details                                       |
| `LogManagementController` | `/api/logs`     | List and read session log files                                           |

---

## Web UI

JTE templates + Tailwind CSS + Alpine.js, served at `/ui` (e.g. `/ui`, `/ui/history`, `/ui/control`) — live DAG visualization, execution history, and a control console. Built with esbuild; see `ui/CLAUDE.md`.

---

## Getting Started

There are no tagged releases yet, so the only way to run Yukta today is from source.

```bash
git clone https://github.com/infenia/yukta.git
cd yukta

# Run (Spring Boot, JVM)
./gradlew bootRun
# → http://localhost:8080/swagger-ui.html
# → http://localhost:8080/ui

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
    "description": "Quick demo",
    "initiator": "me",
    "projectPath": "/tmp/demo",
    "workflows": {
      "hello": {
        "workflowId": "hello",
        "description": "Echo a greeting",
        "nodes": [
          { "nodeId": "start", "type": "MANUAL", "config": {} },
          { "nodeId": "echo", "type": "PROCESS_EXECUTOR", "config": { "command": ["echo", "Hello from Yukta!"] } }
        ],
        "edges": [{ "source": "start", "target": "echo" }]
      }
    }
  }'

# 2. Start it
curl -X POST http://localhost:8080/api/workflow/start \
  -H "Content-Type: application/json" \
  -d '{ "sessionId": "demo", "workflowId": "hello" }'
# → { "data": { "executionId": "..." }, ... }

# 3. Check status (or use the /stream variant for live SSE updates)
curl http://localhost:8080/api/workflow/demo/status/{executionId}
```

### Contributing / building custom plugins

```bash
./gradlew check          # tests + Checkstyle + PMD + SpotBugs (+ Semgrep if installed)
./gradlew spotlessApply  # format before committing
```

See the root [`CLAUDE.md`](CLAUDE.md) and each module's own `CLAUDE.md` for module-specific build commands and architecture notes, and [`CONTRIBUTING.md`](CONTRIBUTING.md) for the PR workflow.

---

## Tech Stack

- **Java 25** (Gradle toolchain)
- **Spring Boot 4.1.0** (WebFlux)
- **Gradle 9.0**
- **Project Reactor** (non-blocking, `Mono`/`Flux`)
- **GraalVM** (native image target)
- **Spring AI MCP Server**
- **JTE + Tailwind CSS + Alpine.js** (web UI)
- **Go + Cobra** (CLI, `cli` module)

## Repository Layout

Multi-module Gradle monorepo. Each module has its own `CLAUDE.md` with module-specific commands and notes.

- **`boot`** — Spring Boot entry point, GraalVM native image config
- **`build-logic`** — Gradle build conventions (Java, quality gates, JaCoCo)
- **`cli`** — Go CLI for remote server interaction
- **`core`** — DAG orchestration engine, plugin registry
- **`mcp`** — MCP server implementation
- **`messaging`** — Shared messaging abstractions
- **`plugin-api`** — Plugin interfaces (`TriggerPlugin`, `ProcessorPlugin`, `TerminalPlugin`) and store abstractions
- **`plugins`** — Plugin implementations
- **`ui`** — JTE/Tailwind/Alpine.js frontend
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
