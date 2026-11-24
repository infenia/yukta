# Architecture overview

One page on how Yukta fits together and which decision explains each part. It states only what the repository's own guides and ADRs state; follow the links for detail. The same system as C4 diagrams (context, containers, server components): [c4-architecture.md](c4-architecture.md).

## What it is

A reactive, DAG-based workflow server. A **workflow** is JSON: nodes (triggers, processors, terminals) joined by edges that follow named **ports**. A **session** groups workflows. A **run** is one pass through a workflow. You drive it through REST, a web UI, an MCP server for AI agents, or the Go CLI. Concepts: [How Yukta works](../docs/concepts/how-it-works.mdx), [Sessions and workflows](../docs/concepts/sessions-and-workflows.mdx), [Runs](../docs/concepts/runs.mdx).

Stack: Java 25, Spring Boot WebFlux, Project Reactor, GraalVM native image, Spring AI MCP server; React SPA for the UI; Go for the CLI.

## Modules and layering

```text
messaging -> plugin-api -> core -> web / mcp          boot wires everything
                  ^
            plugins (depend on plugin-api and messaging only)
plus/** (AGPL: file stores, PostgreSQL, workers, integration layer)  only boot and plus/** may depend on it
ui (React, built into a jar for boot)      cli (Go)      e2e, architecture (test-only)
```

| Module | Role |
|---|---|
| `plugin-api` | The contracts plugins implement (`ProcessorPlugin`, terminals, triggers, `Message`, input and agent gateways) and the hooks `core` calls |
| `core` | The DAG orchestration engine, scheduler, tracker, supervisors, stores and their in-memory defaults |
| `web`, `mcp` | REST controllers and the MCP tools, both on top of `core` |
| `plugins/**` | Built-in triggers, processors and terminals (`external` loads community plugins) |
| `plus/**` | Persistence in PostgreSQL, remote workers, the enterprise integration layer |
| `boot` | The application, native-image config, the optional-module build switches |

The layering is enforced by ArchUnit tests (`architecture`, `plus/plus-architecture`); rules: [`docs/contributing/architecture.mdx`](../docs/contributing/architecture.mdx). Module guides: [`docs/contributing/modules/`](../docs/contributing/modules/).

## The engine

The engine turns a workflow into Reactor `Flux` pipelines, one per node. A processor is `process(Flux<Message>, config)`. Around that call the engine owns merging of parents, pause/skip/breakpoint valves, retries and `onError`, timeouts, checkpoints and tracing, so a plugin is only a function over messages. Backpressure and delivery: [Delivery and backpressure](../docs/concepts/delivery-and-backpressure.mdx), [Failure model](../docs/concepts/failure-model.mdx), [Time limits](../docs/concepts/time-limits.mdx).

Draft workflows can be test-run without being stored: [ADR 0012](decisions/0012-workflow-test-runs.md).

## Control: supervisors

Every command that steers a run goes through a **supervisor**; each node has exactly one owner; the decision logic is a pluggable **engine** behind the supervisor (`rules`, `http`, `callout`, ...). Supervisors are catalogued and configured like plugins and can be declared in session and workflow definitions.

- [ADR 0002](decisions/0002-supervisor-engine-split.md): ownership and the engine split (wins on conflicts)
- [ADR 0009](decisions/0009-supervisor-catalog.md): the catalog, attachments and definitions
- [Threat model](security/supervisor-threat-model.md), [contract](../docs/reference/supervisors/contract.mdx), [operator guide](../docs/concepts/supervisors.mdx)

## People and agents in the loop

- A plugin can ask for input from a supervisor, a person or an external agent: [ADR 0001](decisions/0001-input-requests.md).
- Everything Yukta tells or asks a person becomes a stored notification: [ADR 0003](decisions/0003-notifications.md).
- Terminal AI agents run as nodes and are watched and steered in one console: [ADR 0004](decisions/0004-agent-console.md); the AI backend layer: [ADR 0010](decisions/0010-ai-backend-and-agent-session.md).

## Plugins

Built-in plugins are Spring beans discovered at build time. Anyone can add a processor or terminal without rebuilding Yukta, in any language, through a manifest and a JSON message contract: [ADR 0008](decisions/0008-community-plugins.md).

## Scale, state and editions

- Community keeps state in memory; durable state, PostgreSQL, high availability and remote workers are Plus, and enterprise security is Enterprise: [ADR 0005](decisions/0005-editions.md). See [Durability and high availability](../docs/concepts/durability-and-high-availability.mdx).
- Processor nodes can run on remote workers over RSocket: [ADR 0006](decisions/0006-remote-workers.md).

## Delivery and quality

- Install channels, signing and the release flow: [ADR 0007](decisions/0007-releases.md), runbook [`release/README.md`](../release/README.md).
- Test tiers above the unit tests (e2e module, CLI e2e, real-server Playwright): [ADR 0011](decisions/0011-test-tiers.md).
- Builds, tests and checks run through `yk` (see the root [`CLAUDE.md`](../CLAUDE.md)).
