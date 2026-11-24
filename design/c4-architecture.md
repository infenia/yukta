# C4 architecture

Yukta at the first three levels of the [C4 model](https://c4model.com): system context, containers, and the main components of the server. It is a high-level map; [architecture.md](architecture.md) and the ADRs explain the decisions, and the module guides explain the code. Level 4 (code) is left to the code.

Legend for all diagrams: **blue** is Yukta, **grey** is outside Yukta, **dashed** is Plus edition (AGPL, see [ADR 0005](decisions/0005-editions.md)) or optional.

## Level 1: System context

Who uses Yukta and what it talks to.

```mermaid
flowchart TB
  dev(["<b>Developer / operator</b><br/>[Person]<br/>Defines workflows, runs and watches them, answers approvals"])
  agent(["<b>AI coding agent</b><br/>[Person / external system]<br/>e.g. Claude Code; drives Yukta over MCP"])

  yukta["<b>Yukta</b><br/>[Software system]<br/>Reactive DAG workflow server: runs workflows of trigger, processor<br/>and terminal nodes; supervises runs; asks people and agents for input"]

  llm["<b>LLM providers</b><br/>[External system]<br/>Anthropic, OpenAI, Ollama"]
  tools["<b>Local tools and agent CLIs</b><br/>[External system]<br/>Build tools, scripts, claude / codex in a PTY,<br/>community plugins (process, container, MCP)"]
  ext["<b>External services</b><br/>[External system]<br/>HTTP APIs, SQL databases, MCP servers,<br/>webhook senders"]
  notify["<b>Notification receivers</b><br/>[External system]<br/>Webhook endpoints"]
  otel["<b>Observability backend</b><br/>[External system]<br/>OTLP collector (off by default)"]

  dev -- "Uses (browser, CLI, REST)" --> yukta
  agent -- "Calls tools (MCP, streamable HTTP)" --> yukta
  yukta -- "Prompts (HTTPS)" --> llm
  yukta -- "Starts processes / containers" --> tools
  yukta -- "Calls; receives triggers" --> ext
  yukta -- "Sends notifications (HTTPS)" --> notify
  yukta -. "Exports traces and metrics (OTLP)" .-> otel

  classDef person fill:#08427b,stroke:#052e56,color:#fff
  classDef system fill:#1168bd,stroke:#0b4884,color:#fff
  classDef external fill:#999,stroke:#6b6b6b,color:#fff
  class dev,agent person
  class yukta system
  class llm,tools,ext,notify,otel external
```

| Element | Notes |
|---|---|
| Developer / operator | Holds an API key with a role (`VIEWER`, `OPERATOR`, `ADMIN`). The server listens on loopback until `YUKTA_SERVER_ADDRESS` and `yukta.api-keys` open it. |
| AI coding agent | Uses the same engine as a person: start runs, read failure digests, answer input requests, act as a supervisor. |
| LLM providers | Reached by the `llm-call` and `decision` plugins and the assistant. |
| Local tools and agent CLIs | `process-executor` runs commands; `agent-session` runs terminal agents; community plugins run out of process ([ADR 0008](decisions/0008-community-plugins.md)). |
| External services | `http-request`, `sql-query`, `mcp-tool-call` call out; `webhook-trigger`, `api-trigger` and `schedule-trigger` start runs. |

## Level 2: Containers

The separately runnable or deployable parts.

```mermaid
flowchart TB
  dev(["<b>Developer / operator</b><br/>[Person]"])
  agent(["<b>AI coding agent</b><br/>[External system]"])

  subgraph yukta ["Yukta"]
    direction TB
    ui["<b>Web UI</b><br/>[Container: React SPA, Vite]<br/>Sessions, workflow editor, runs, agent console,<br/>inbox, supervisors. Served by the server"]
    cli["<b>CLI</b><br/>[Container: Go binary]<br/>Remote control of a server from a terminal"]
    server["<b>Yukta server</b><br/>[Container: Spring Boot WebFlux, Reactor;<br/>JVM jar, GraalVM native image or Docker]<br/>REST API, MCP server, DAG engine,<br/>supervisors, built-in plugins"]
    worker["<b>Remote worker</b><br/>[Container: same server build, worker role]<br/>Runs processor nodes for a control plane"]
    db[("<b>PostgreSQL</b><br/>[Container: database, R2DBC]<br/>Durable sessions, runs, checkpoints;<br/>leader election")]
  end

  ext["<b>External systems</b><br/>[LLMs, tools, services, receivers, OTLP]"]

  dev -- "Uses (HTTPS)" --> ui
  dev -- "Runs" --> cli
  ui -- "REST + SSE (JSON)" --> server
  cli -- "REST (JSON)" --> server
  agent -- "MCP /mcp (streamable HTTP)" --> server
  server -. "Reads/writes (R2DBC)" .-> db
  worker -. "Connects out, registers, runs nodes (RSocket, TLS)" .-> server
  server -- "Plugins call out" --> ext
  worker -. "Plugins call out" .-> ext

  classDef person fill:#08427b,stroke:#052e56,color:#fff
  classDef container fill:#438dd5,stroke:#2e6295,color:#fff
  classDef plus fill:#438dd5,stroke:#2e6295,color:#fff,stroke-dasharray:5 5
  classDef external fill:#999,stroke:#6b6b6b,color:#fff
  class dev,agent person
  class ui,cli,server container
  class worker,db plus
  class ext external
```

| Container | Technology | Module(s) | Edition |
|---|---|---|---|
| Yukta server | Java 25, Spring Boot WebFlux, Project Reactor, Spring AI MCP; jar, native image, Docker image | `boot` (wires `core`, `web`, `mcp`, `plugins/**`, `messaging`, `plugin-api`) | Community |
| Web UI | React 19, Vite, Tailwind; built into a static jar served by the server | `ui` | Community |
| CLI | Go | `cli` | Community |
| Remote worker | The server build started in the worker role; RSocket over TCP or TLS/mTLS | `plus/workers` ([ADR 0006](decisions/0006-remote-workers.md)) | Plus |
| PostgreSQL | R2DBC stores and leader election for high availability | `plus/persistence-postgres` | Plus |

Community keeps all state in memory in the server process. Plus adds durable state, several servers behind one leader, and workers.

## Level 3: Components of the Yukta server

The main building blocks inside the server, grouped by module. Arrows are "uses".

```mermaid
flowchart TB
  clients(["UI, CLI, AI agents"])

  subgraph api ["API layer"]
    direction LR
    rest["<b>REST controllers</b><br/>[web]<br/>/api/sessions, workflows, inputs,<br/>supervisors, notifications, agent"]
    mcp["<b>MCP tools</b><br/>[mcp]<br/>Session, run, log, supervisor tools"]
    sec["<b>Security</b><br/>[core security]<br/>API keys, roles, rate limit, audit"]
  end

  subgraph core ["core"]
    direction TB
    sessions["<b>Sessions and workflows</b><br/>Definitions, versions, validation"]
    engine["<b>Orchestrator (DAG engine)</b><br/>One Reactor Flux per node; retries, timeouts,<br/>pause/skip/breakpoints, checkpoints, tracing"]
    sched["<b>Scheduler and limits</b><br/>Admission, concurrency, worker dispatch"]
    sup["<b>Supervisors and control bus</b><br/>Node ownership; every steering command"]
    input["<b>Input requests, notifications,<br/>agent conversations</b><br/>People and agents in the loop"]
    stores["<b>Stores</b><br/>Runs, logs, results, checkpoints;<br/>in-memory by default"]
  end

  subgraph ext ["Extension points"]
    direction LR
    papi["<b>plugin-api + messaging</b><br/>Plugin contracts, Message"]
    plugins["<b>Built-in plugins</b><br/>[plugins/**]<br/>Triggers, processors, terminals,<br/>supervisor engines"]
    plus["<b>Plus</b><br/>[plus/**]<br/>PostgreSQL stores, leader election,<br/>worker transport"]
  end

  clients --> rest
  clients --> mcp
  rest --> sec
  mcp --> sec
  rest --> sessions
  mcp --> sessions
  rest --> sup
  mcp --> sup
  sessions --> engine
  sup --> engine
  engine --> sched
  engine --> input
  engine --> stores
  engine -- "calls process(Flux of Message)" --> papi
  plugins -- "implement" --> papi
  plus -. "implements store and worker hooks" .-> stores
  plus -.-> sched

  classDef comp fill:#85bbf0,stroke:#5d82a8,color:#000
  classDef plusc fill:#85bbf0,stroke:#5d82a8,color:#000,stroke-dasharray:5 5
  classDef person fill:#08427b,stroke:#052e56,color:#fff
  class rest,mcp,sec,sessions,engine,sched,sup,input,stores,papi,plugins comp
  class plus plusc
  class clients person
```

| Component | Where | What it does |
|---|---|---|
| REST controllers | `web` | HTTP API and SSE streams the UI and CLI use; OpenAPI at `/swagger-ui.html` |
| MCP tools | `mcp` | The same operations as tools for AI agents at `/mcp` |
| Security | `core` `security` | API-key authentication, roles, call rate limits, audit records |
| Sessions and workflows | `core` `service.session`, `service.workflow` | Store and version workflow definitions (JSON nodes and edges), validate them |
| Orchestrator | `core` `service.orchestrator` | Turns a workflow into Reactor pipelines and runs it; owns retries, timeouts, checkpoints ([delivery](../docs/concepts/delivery-and-backpressure.mdx), [failure model](../docs/concepts/failure-model.mdx)) |
| Scheduler and limits | `core` `service.runtime`, `service.worker` | Admits runs within limits, sends node attempts to local execution or a remote worker |
| Supervisors and control bus | `core` `service.supervisor`, `service.control` | Each node has one owner; pause, resume, stop, approve go through it; decision engines (`rules`, `http`, `process-engine`) plug in ([ADR 0002](decisions/0002-supervisor-engine-split.md)) |
| Input, notifications, agent conversations | `core` `service.input`, `service.notify`, `service.agent` | Ask a person or agent and wait ([ADR 0001](decisions/0001-input-requests.md)); inbox and webhook delivery ([ADR 0003](decisions/0003-notifications.md)); agent console ([ADR 0004](decisions/0004-agent-console.md)) |
| Stores | `core` `service.store` and siblings | In-memory stores behind interfaces; Plus supplies PostgreSQL ones |
| plugin-api, messaging | `plugin-api`, `messaging` | Contracts every plugin compiles against; no web or data stack |
| Built-in plugins | `plugins/**` | Triggers (`api`, `webhook`, `schedule`, `constant-source`), processors (`process-executor`, `agent-session`, `http-request`, `sql-query`, `llm-call`, `mcp-tool-call`, `file-io`, `decision`, internal, `script-js`), terminals (`console`, `notify`, `outcome`), community plugin loader (`external`) |
| Plus | `plus/**` | Durable stores, leader election, worker control plane |

The dependency direction between these modules is enforced by ArchUnit: see [Architecture rules](../docs/contributing/architecture.mdx).

## How a run flows

1. A trigger fires: a REST or MCP `start`, a webhook, or a schedule.
2. The orchestrator builds the DAG's pipelines; the scheduler admits the run.
3. Each node's plugin processes messages; the engine routes outputs along edges by port.
4. Supervisors may pause, approve or stop nodes; a plugin may ask a person or agent for input.
5. Logs, results and checkpoints go to the stores; the UI follows progress over SSE.
6. Terminal nodes record the outcome and may send notifications.
