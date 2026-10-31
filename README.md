# Yukta

**Define the process once. Make sure it is followed every time, and keep control while it runs.**

Yukta is a reactive, DAG-based workflow orchestration server. A workflow is a JSON graph that scripts, CI pipelines, people and AI agents all run the same way.

[Docs](https://yukta.infenia.com/) ·
[Install](#install) ·
[Quick start](#quick-start) ·
[Interfaces](#interfaces) ·
[Examples](examples/README.md) ·
[Contributing](#contributing)

[![CI](https://github.com/infenia/yukta/actions/workflows/ci.yml/badge.svg)](https://github.com/infenia/yukta/actions/workflows/ci.yml)
[![CodeQL](https://github.com/infenia/yukta/actions/workflows/codeql.yml/badge.svg)](https://github.com/infenia/yukta/actions/workflows/codeql.yml)
[![codecov](https://codecov.io/gh/infenia/yukta/branch/main/graph/badge.svg)](https://codecov.io/gh/infenia/yukta)
[![OpenSSF Scorecard](https://api.scorecard.dev/projects/github.com/infenia/yukta/badge)](https://scorecard.dev/viewer/?uri=github.com/infenia/yukta)
[![Release](https://img.shields.io/github/v/release/infenia/yukta?include_prereleases)](https://github.com/infenia/yukta/releases)
[![License: Apache 2.0 / AGPL-3.0](https://img.shields.io/badge/license-Apache%202.0%20%2B%20AGPL--3.0-blue)](LICENSING.md)

## The problem

You know how the work should go: build, test, review, get approval, deploy. You write it down in a wiki page, a README, a prompt or a script. Then the work runs, and the process quietly stops being followed.

- **Steps get skipped.** A check is "optional this once". A validation is assumed to have passed. Something reports "done" when it never ran the verification you asked for.
- **Instructions are suggestions.** A process written in a document or a prompt relies on whoever (or whatever) reads it to comply. Nothing enforces the order, and nothing proves a step happened.
- **A running job is a black box.** When something looks wrong halfway through, you can usually only wait or kill it. You cannot pause it, look at what it has done, fix the input and carry on from where it stopped.
- **It gets worse with more actors.** The moment several scripts, services or AI agents share one process, nobody can say who owns which step, or what state the whole thing is in.

If you have ever re-run a pipeline from the start because you could not resume it, or found out later that a gate was never checked, this is that problem.

## What Yukta does

Yukta separates *what must happen* from *who does the work*. The process lives in the server as a graph, and the server enforces it.

- **The workflow is the source of truth.** A workflow is a JSON graph of nodes and edges. Steps run in the order the graph says, nothing is skipped by accident, and routing (for example `success` and `failure` paths, or one path per `BRANCH` case) is part of the definition. There is no DSL to learn, and a graph is easy for people, tools and AI agents to generate and review.
- **Quality gates are nodes, not hopes.** Tests, checks, approvals and model-based decisions (`GATE`, `CLASSIFY`, `SCORE`, `ASK`) sit in the graph. Work that fails a gate does not reach the next step.
- **Full control while it runs.** Pause, resume, stop, restart, skip, retry, step through, and set breakpoints, on a whole run or on a single node. Inspect any node's output, then continue.
- **Agents are first-class.** An AI agent can start workflows, watch them, pause or restart them and answer approvals through the built-in [MCP](https://modelcontextprotocol.io/) server. The agent runs inside the process you defined, and still has the controls it needs.
- **From a script to many agents.** The same engine runs a three-step shell pipeline, a CI/CD flow, a data pipeline or several cooperating agents. Fan out, wait for signals, route by result, loop until ready.
- **One definition, many callers.** Start the same workflow from REST, the web UI, the Go CLI or an MCP client such as Claude.
- **Observable.** Live DAG view with progress, SSE log and status streams, and run history.
- **Reactive end to end.** Built on Spring WebFlux and Project Reactor. Nodes are non-blocking streams, and I/O runs on virtual threads.
- **Extensible.** Plugins are Spring beans implementing a small interface from `plugin-api`. Adding one does not touch `core`.
- **Safe to start with.** It binds to loopback by default, and an API key guards `/api/**` and `/mcp` when you expose it.

> **Alpha (`0.1.0-alpha.2`).** The engine, plugins, REST API, MCP server, web UI and CLI run and are covered by tests. Expect breaking changes to the workflow JSON and the APIs before `0.1.0`. The native server is Linux amd64 only for now; macOS and Windows run it with Docker. See [Releases](https://yukta.infenia.com/reference/releases).

## Install

> **Tested on Linux amd64 only.** The install methods below have been verified on Linux amd64. The Windows, macOS, Docker, Debian/RPM and from-source routes are provided but have not been tested on other machines or architectures, so they may not work as described. If you hit a problem, please [open an issue](https://github.com/infenia/yukta/issues/new/choose) with your OS and architecture.

| Platform | Command |
|----------|---------|
| Linux (server + CLI) | `curl -fsSL https://github.com/infenia/yukta/releases/download/v0.1.0-alpha.2/install.sh \| sh` |
| Windows (CLI) | `irm https://github.com/infenia/yukta/releases/latest/download/install.ps1 \| iex` |
| macOS (CLI) | download `yukta-cli-darwin-arm64` or `-amd64` from the [release](https://github.com/infenia/yukta/releases/tag/v0.1.0-alpha.2) |
| Any (Docker) | `docker run --rm -p 127.0.0.1:9858:9858 -e YUKTA_API_KEY=change-me ghcr.io/infenia/yukta:0.1.0-alpha.2` |
| Debian / RPM | `.deb` and `.rpm` packages on the [releases page](https://github.com/infenia/yukta/releases) |
| From source | JDK 25, then `./gradlew bootRun` (see [Develop](#develop)) |

Release files carry `SHA256SUMS` and a Sigstore signature. See [Deployment](https://yukta.infenia.com/operations/deployment) for upgrades, packages and verification.

### Run modes

`yukta` starts with a live terminal dashboard (health, run counts, recent runs and logs) when it runs in a terminal, and with plain log lines otherwise (service, container, pipe). Set the mode with `--mode=console|plain`, `--plain` or `YUKTA_MODE`; `NO_COLOR` turns the colors off. To run it in the background:

```bash
yukta start      # detached; waits until healthy
yukta status     # also: stop, restart, logs [-f]
```

Under systemd or in Docker the server uses plain mode on its own. See [Run modes](https://yukta.infenia.com/running/run-modes).

## Quick start

Start the server (`yukta`, or the Docker command above), then open the web UI at <http://localhost:9858/> or the API docs at <http://localhost:9858/swagger-ui.html>. With the Docker command, add `-H "X-API-Key: change-me"` to each `curl`.

Create a session with one workflow, start it and read the result:

```bash
# 1. Create a session containing a workflow
curl -X POST http://localhost:9858/api/sessions -H "Content-Type: application/json" -d '{
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
        { "nodeId": "start", "type": "CONSTANT_SOURCE", "config": {} },
        { "nodeId": "echo",  "type": "PROCESS_EXECUTOR", "config": { "command": ["echo", "Hello from Yukta!"] } },
        { "nodeId": "done",  "type": "CONSOLE_TERMINAL", "config": {} }
      ],
      "edges": [
        { "source": "start", "target": "echo" },
        { "source": "echo",  "target": "done" }
      ]
    }
  }
}'

# 2. Start it (returns 202 with data.executionId)
curl -X POST http://localhost:9858/api/workflow/start -H "Content-Type: application/json" \
  -d '{ "sessionId": "demo", "workflowId": "hello" }'

# 3. Check the status, or follow it live over SSE
curl http://localhost:9858/api/executions/{executionId}
curl -N http://localhost:9858/api/executions/{executionId}/status/stream
```

Sessions are held in memory, so apply them again after a restart. For a longer walk-through see the [Quickstart](https://yukta.infenia.com/quickstart).

## Interfaces

| Interface | What it is | Guide |
|-----------|------------|-------|
| **REST API** | `/api`, with Swagger UI at `/swagger-ui.html`; SSE for status, logs and events | [API reference](https://yukta.infenia.com/api/overview) |
| **MCP server** | `/mcp` in the same process: 30 tools, 2 prompts and 3 resources so an agent can create sessions, start workflows and read logs | [MCP guide](https://yukta.infenia.com/interfaces/mcp) |
| **Web UI** | React 19 + Vite + Tailwind CSS 4 at `/`: live DAG view with progress and logs, run history, control console, plugin catalogue and a form-based workflow editor | [Web UI guide](https://yukta.infenia.com/interfaces/web-ui) |
| **CLI** | `yukta-cli`, a single static Go binary for Linux, macOS and Windows | [CLI guide](https://yukta.infenia.com/interfaces/cli), [`cli/`](cli/README.md) |
| **Claude Code plugin** | `/yukta` pane for running executions and approvals, toasts, and the MCP server inside Claude Code | [`claude-mods/yukta`](claude-mods/yukta/README.md) |

Connect Claude Code to the MCP server:

```bash
claude mcp add --transport http yukta http://localhost:9858/mcp
# with an API key:
claude mcp add --transport http yukta http://localhost:9858/mcp --header "Authorization: Bearer <key>"
```

Or install the plugin, which adds the live view as well (set `server_url` to `http://localhost:9858` in `/config`; the plugin defaults to the dev server on `:9858`):

```
/plugin marketplace add infenia/yukta
/plugin install yukta@yukta
```

## How it works

A **session** groups workflows. A **workflow** is a DAG of nodes and edges. Each node is a plugin instance with its own `config`. A run is an **execution** that you can watch, pause, stop and restart.

Every workflow needs at least one **trigger** (no inbound edges) and at least one **terminal** (no outbound edges), with **processors** in between. The orchestrator checks reachability, cycles and entry points before it runs anything. Processors can expose named output ports (for example `success` and `failure`, or one port per `BRANCH` case), so one node can route messages down different paths. See [How it works](https://yukta.infenia.com/concepts/how-it-works).

### Plugins

37 built-in plugins, grouped as:

- **Triggers:** `API_TRIGGER`, `WEBHOOK_TRIGGER`, `SCHEDULE_TRIGGER`, `CONSTANT_SOURCE`
- **Processors:** run processes and HTTP, SQL and file I/O; route (`BRANCH`, `FILTER`, `SPLITTER`, `RECIPIENT_LIST`, loops); transform (`MAPPER`, `AGGREGATE`); wait for signals and approvals; call LLMs and MCP tools; make model decisions (`CLASSIFY`, `GATE`, `SCORE`, `ASK`)
- **Terminals:** `CONSOLE_TERMINAL`, `NOTIFY_TERMINAL`
- **Supervisors and control planes:** declarative rules, or a model, that gate and decide on control-bus events (opt-in)

Browse them in the [plugin docs](https://yukta.infenia.com/plugins/overview), with `GET /api/plugins/{type}`, or in the web UI. To write your own, implement `TriggerPlugin`, `ProcessorPlugin` or `TerminalPlugin` from `plugin-api` and register it as a Spring bean ([guide](https://yukta.infenia.com/plugins/writing-a-plugin)).

### Examples

[`examples/`](examples/README.md) has ready-to-run workflows, each with a config, a trigger and a test script. Ten are templates for a real pain point in a different software domain: an agent that must not skip verification, release approval (DevOps), a PR risk gate, dependency-update triage, crash-report triage, an event data pipeline (data engineering), alert triage (SRE), model promotion (MLOps), security-finding triage (AppSec) and a multi-agent team. The rest show the basics, an LLM chat agent, driving Yukta over MCP, and how Yukta checks its own code. Run them all against a local server with `examples/run-all.sh`.

## Security

The server listens on `127.0.0.1` by default. To expose it, set `YUKTA_SERVER_ADDRESS=0.0.0.0` **and** `yukta.api-key`; the server refuses to start on another address without a key. Keys have roles (`VIEWER`, `OPERATOR`, `ADMIN`). `PROCESS_EXECUTOR` refuses every command until you allow some, and workflows can run processes, scripts and SQL, so treat workflow authorship as a trusted operation. See [Security](https://yukta.infenia.com/operations/security) and report vulnerabilities as described in [`SECURITY.md`](SECURITY.md).

## Documentation

Everything else is at **[yukta.infenia.com](https://yukta.infenia.com/)**:
[Concepts](https://yukta.infenia.com/concepts/how-it-works) ·
[Building workflows](https://yukta.infenia.com/building/defining-a-workflow) ·
[Plugins](https://yukta.infenia.com/plugins/overview) ·
[Running and controlling](https://yukta.infenia.com/running/run-controls) ·
[Operations](https://yukta.infenia.com/operations/deployment) (configuration, persistence, high availability) ·
[Reference](https://yukta.infenia.com/reference/limits-and-defaults)

## Develop

Built with Java 25, Spring Boot 4.1 (WebFlux), Gradle 9.8, Project Reactor, Spring AI MCP, GraalVM, React 19 and Go.

```bash
./gradlew bootRun          # run the server
./gradlew nativeCompile    # optional GraalVM native image (needs a GraalVM JDK)
./gradlew check            # tests + Checkstyle + PMD + SpotBugs + JaCoCo
./gradlew spotlessApply    # format before committing
```

**Developing Yukta with Yukta.** Contributors (and Claude Code, held to it by hooks) run builds, tests and checks through `examples/11-dogfood/yk`: each Gradle call becomes a Yukta workflow on a local dev server built from `origin/main` at `http://127.0.0.1:9858`, and `yk check` is the staged, self-healing quality gate. See [`examples/11-dogfood/README.md`](examples/11-dogfood/README.md).

**Persistence.** Sessions, workflows and the execution log live in memory by default. File storage is in the Plus build (`-Pyukta.edition=plus`, AGPL-3.0-only); PostgreSQL is `./gradlew bootJar -Pyukta.persistence=postgres`. See [Persistence](https://yukta.infenia.com/operations/persistence).

| Module | Purpose |
|--------|---------|
| `core` | DAG orchestration engine, plugin registry |
| `plugin-api` | Plugin interfaces and store abstractions |
| `plugins` | Built-in triggers, processors, terminals, supervisors, control planes |
| `web` / `mcp` | REST controllers / MCP server |
| `ui` / `cli` | React frontend / Go command-line client |
| `boot` | Spring Boot entry point, native image config |
| `plus/persistence-postgres` | Optional (AGPL) PostgreSQL stores |
| `claude-mods` | Claude Code plugin |
| `messaging`, `build-logic`, `examples` | Shared messaging, Gradle conventions, runnable workflows |

Each module has its own `CLAUDE.md` with build commands and architecture notes.

## Contributing

Contributions are welcome: bug reports, plugins, docs and tests.

1. Read [`CONTRIBUTING.md`](CONTRIBUTING.md) and the [Code of Conduct](CODE_OF_CONDUCT.md).
2. Branch from `main`, format with `spotlessApply`, and make sure `check` passes.
3. Use [Conventional Commits](https://www.conventionalcommits.org/) and open a pull request.

Ask questions in [Discussions](https://github.com/infenia/yukta/discussions); report bugs and request features as [issues](https://github.com/infenia/yukta/issues/new/choose). Project rules are in [`GOVERNANCE.md`](GOVERNANCE.md).

## License

Apache License 2.0 for the Community edition. The `plus/` directory (durable storage) is AGPL-3.0-only, with a commercial licence available. See [`LICENSING.md`](LICENSING.md) and [`LICENSE`](LICENSE). The repository follows the [REUSE](https://reuse.software/) specification.

---

Developed by **[Infenia Private Limited](https://infenia.com)**. *Yukta* (युक्त) is Sanskrit for "united" or "joined."
