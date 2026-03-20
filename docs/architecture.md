# Architecture & Design

This document provides a detailed overview of Yukta's architecture, internal mechanisms, and design patterns.

## High-Level Overview

Yukta is a reactive DAG (Directed Acyclic Graph) orchestrator built on Enterprise Integration Patterns (EIP). It accepts workflow definitions as JSON, validates them, compiles them into optimized execution pipelines, and executes them reactively with sub-100ms feedback loops. Three layers handle transport (REST/MCP), orchestration (reactive scheduling), and execution (composable plugins).

**Key design priorities**: Modularity (plugins are independent), Security (validation at all boundaries), Performance (reactive non-blocking throughout).

## 3-Layer Architecture

```
┌───────────────────────────────────────────┐
│ Transport Layer  (@web, @mcp modules)     │
│  REST API (16 endpoints) | MCP (13 tools) │
└─────────────────┬─────────────────────────┘
                  │
┌─────────────────▼─────────────────────────┐
│ Orchestration Layer  (@core module)        │
│  WorkflowOrchestrator | SessionService     │
│  TaskTrackerService | ControlBusService    │
└─────────────────┬─────────────────────────┘
                  │
┌─────────────────▼─────────────────────────┐
│ Plugin Layer  (@plugin-api, @plugins)      │
│  TriggerPlugin | ProcessorPlugin           │
│  TerminalPlugin | Message<T> API           │
└───────────────────────────────────────────┘
```

---

## 1. Plugin Layer

### WorkflowPlugin Interface

All plugins implement `WorkflowPlugin` with these lifecycle methods:

```java
// Unique type string (e.g., "PROCESS_EXECUTOR", "BRANCH", "api-trigger")
String getType();

// Category: TRIGGER, PROCESSOR, or TERMINAL
PluginCategory getCategory();

// Validate configuration structure (field presence, types, constraints)
Mono<Void> validateConfig(Map<String, Object> config);

// Validate structural constraints (e.g., BRANCH must have outgoing edges)
Mono<Void> validateInContext(WorkflowContext context, Map<String, Object> config);

// Pre-execution (e.g., pre-compile SpEL expressions in BRANCH/MAPPER)
Mono<Void> prepare(Map<String, Object> config);

// Lifecycle: called once before first execution
Mono<Void> initialize(Map<String, Object> config);

// Lifecycle: called once after last execution
Mono<Void> shutdown(Map<String, Object> config);

// True = run on virtual thread scheduler (for blocking I/O)
boolean isBlocking();

// Receive admin commands from ControlBus
Mono<Message<?>> onControlSignal(Message<?> controlSignal);

// Declare routing ports (default: ["default"])
List<String> getOutputPorts(Map<String, Object> config);

// For AI agent discoverability
String getDescription();
String getUsagePattern();
```

### Plugin Categories

| Category | Interface | Method Signature | Role in DAG |
|----------|-----------|------------------|-------------|
| **TRIGGER** | `TriggerPlugin` | `Flux<Message<?>> start(Map config)` | Source — initiates workflow; no incoming edges |
| **PROCESSOR** | `ProcessorPlugin` | `Flux<Message<?>> process(Flux<Message<?>> input, Map config)` | Transforms/routes messages; must have incoming and outgoing edges |
| **TERMINAL** | `TerminalPlugin` | `Mono<Void> consume(Flux<Message<?>> input, Map config)` | Sink — consumes messages; no outgoing edges |

### Message<T> API

All plugins communicate via immutable `Message<T>` envelopes:

**Core fields**:
- Identity: `messageId`, `correlationId`, `replyTo`, `traceId`, `timestamp`
- Routing: `sourcePort`, `sourceNodeId`
- Payload: `getPayload(): T`, `getPayloadType(): Class<T>`
- Metadata: `getMetadata(): Map<String, Object>`

**Sequence tracking** (Splitter/Aggregator):
- `sequenceId`, `sequenceNumber`, `sequenceSize`

**QoS and dead-lettering**:
- `priority`, `isControlMessage`
- `origDest`, `failureReason`, `exceptionDetail`, `retryCount`

**Wither pattern** (all mutations return new instances):
```java
message
    .withPayload(newData)
    .withSourcePort("success")
    .withHeader("X-Custom", "value")
    .withAddedHistory(nodeId)
```

**Factory methods**:
- `DefaultMessage.create(traceId, payload)` — new message
- `DefaultMessage.from(original, newPayload)` — copy with new payload

### Gateway Interfaces (Injectable by Plugins)

- **MessagingGateway** — async request-reply or fire-and-forget
- **WorkflowGateway** — trigger sub-workflows from within plugins
- **ControlBusGateway** — emit heartbeats, statistics, register/unregister from bus
- **BuildGateway** — OS process execution (used by PROCESS_EXECUTOR plugin)

### Store Interfaces

- **MessageStore** — persist/retrieve messages for audit trail
- **ClaimCheckStore** — offload large payloads by reference key
- **IdempotencyStore** — deduplicate messages by `correlationId`
- **SecretProvider** — resolve encrypted secrets (default: no-op)

---

## 2. Orchestration Layer

Located in `core/src/main/java/com/infenia/yukta/service/`, this layer handles workflow preparation, validation, compilation, and execution.

### Workflow Execution Flow

```
WorkflowService.runWorkflow(sessionId, workflowId, payload)
  ↓
[Queueing]: Enqueue per "sessionId:workflowId" key (prevents concurrent runs of same workflow)
  ↓
SessionConfigStore.getWorkflow(sessionId, workflowId) → WorkflowDefinition
  ↓
WorkflowOrchestrator.prepareWorkflow(definition)
  ├─ WorkflowValidator.validate(definition) — 8-step validation
  ├─ plugin.initialize(config) for each node
  ├─ ControlBusGateway.registerPlugin(nodeId, plugin)
  ├─ TopologicalSortService.computeTopologicalOrder() — Kahn's algorithm
  └─ compileTemplate() → WorkflowTemplate (pre-compiled execution lambda)
  ↓
WorkflowOrchestrator.execute(sessionId, workflowId, executionId, template, payload)
  ├─ UUID executionId generated
  ├─ WorkflowTemplate.instantiate(executionId, payload)
  ├─ TaskTrackerService.startWorkflow(executionId, ...) — create execution state
  ├─ executeTemplate() — build NodeAssembler per node
  ├─ HeartbeatBuilder.build() → Flux.interval() every 10s per node
  ├─ ResourceManagementBuilder.build() → Mono.using() lifecycle
  │   ├─ runConnectors() in reverse topological order
  │   ├─ Mono.whenDelayError(terminals) — wait for all terminal nodes to complete
  │   ├─ On success: emitWorkflowStatusEvent("SUCCESS")
  │   ├─ On error: emitWorkflowStatusEvent("ERROR"), propagate error
  │   └─ Cleanup: dispose all heartbeat subscriptions, close resources
  └─ TaskTrackerService.endWorkflow(executionId)
```

### WorkflowValidator (8-Step Validation)

Executed in order; any failure stops pipeline:

1. **validatePluginsRegistered** — all node `type` values exist in `WorkflowRegistry`
2. **validateEntryPoints** — nodes without incoming edges must be `TriggerPlugin` (e.g., `api-trigger`)
3. **validateProcessors** — processor nodes without incoming edges must have outgoing edges
4. **validateEndpoints** — nodes without outgoing edges must be `TerminalPlugin` (e.g., `console`)
5. **validateNoCycles** — DFS-based cycle detection across all edges
6. **validateNoOrphans** — implicit; handled by steps 1–4
7. **validateNodeContexts** — per-plugin `validateInContext()` (e.g., BRANCH verifies edge ports match declared ports)
8. **validatePluginConfigs** — per-plugin `validateConfig()` (e.g., PROCESS_EXECUTOR requires `command` field)

### Session Lifecycle

- **Creation**: `POST /api/config` → `SessionService.applyConfig()` creates new session
- **Storage**: `SessionConfigStore` (in-memory or file: `/tmp/.yukta/sessions/{sessionId}.json`)
- **Fields**: `sessionId`, `description`, `initiator`, `tags`, `projectPath`, `workflows` (Map)
- **Persistence**: Sessions live across workflow runs; re-posting `/api/config` with same sessionId overwrites config

### TaskTrackerService

Tracks all execution states:

- **In-memory index**: O(1) lookup by executionId (dual-indexed: `sessionStates` + `executionIndex`)
- **Event sinks** (batched 100 events or 50ms):
  - `taskStatusSink` — per-task completion events
  - `wfStatusSink` — workflow completion events
  - `logSink` — structured logs (JSONL)
- **SSE streaming**: `getStatusStream(executionId)` → `Flux<WorkflowProgress>` (auto-subscribes)
- **Auto-cleanup**: TTL-based cleanup after configurable duration (`yukta.tracker.cleanup-ttl: 10m`)

**WorkflowProgress** (status response shape):
```json
{
  "executionId": "uuid",
  "sessionId": "...",
  "workflowId": "...",
  "status": "RUNNING|SUCCESS|FAILURE|ERROR",
  "tasks": [
    {
      "nodeId": "...",
      "module": "...",
      "status": "...",
      "startTime": "...",
      "endTime": "...",
      "metadata": {}
    }
  ],
  "startTime": "...",
  "endTime": null
}
```

### ControlBus

Multi-cast event bus for workflow monitoring and admin commands:

- **Multicast sink**: `Sinks.Many` batching control signals (100 msgs or 50ms)
- **Heartbeats**: Emitted every 10s per active node (contains node ID, timestamp, stats)
- **Handlers**:
  - `ControlHeartbeatHandler` — logs/processes heartbeats
  - `ControlStatisticsHandler` — aggregates node statistics
- **Admin commands**: Plugins receive admin signals via `onControlSignal(Message)`
- **REST**: `GET /api/control/stream` streams all signals as Server-Sent Events
- **Signal types**: `ControlHeartbeat`, `ControlStatistics`, `ControlConfiguration`, `ControlError`

### Internal Builder Pattern

Four internal builder classes (in `service/orchestrator/`) manage cross-cutting concerns:

#### ExecutionContextBuilder
- Centralizes Reactor Context propagation (`sessionId`, `workflowId`, `executionId`, `nodeId`, `payload`)
- Fluent API: `new ExecutionContextBuilder().sessionId(...).workflowId(...).build()`
- Applies context to Mono/Flux streams via `withContext()`

#### StreamBuilder
- Unifies stream construction across all plugin types (Trigger, Processor, Terminal)
- Handles: timeout wrapping, task status tracking, error handling, context application
- Eliminates 60+ lines of duplicated code across three assembler methods
- Usage: `new StreamBuilder(...).withSource(...).withTimeout().withTaskTracking(...).build()`

#### HeartbeatBuilder
- Manages periodic heartbeat and statistics emissions to ControlBus
- Encapsulates Flux.interval() subscriptions and disposable lifecycle
- Usage: `new HeartbeatBuilder(...).forNodes(...).withHeartbeatInterval(...).build()`

#### ResourceManagementBuilder
- Wraps Mono.using() pattern for resource lifecycle
- Manages timeouts, terminal completion, connector execution, disposable cleanup
- Centralizes workflow status event emission
- Usage: `new ResourceManagementBuilder(...).withDisposables(...).withTerminals(...).build()`

**Refactoring impact**:
- Node assembler methods: ~70 → ~25 lines (65% reduction)
- executeTemplate method: ~135 → ~50 lines (63% reduction)
- Preserved all functionality and Reactor semantics; no performance impact

---

## 3. Transport Layer

### REST API (16 Endpoints)

**Configuration** (`ConfigController`):
- `POST /api/config` — Initialize/update session config

**Workflow Execution** (`AppController`):
- `POST /api/workflow/trigger` → 202 + `{executionId}`
- `GET /api/workflow/{sessionId}/status/{executionId}`
- `GET /api/workflow/{sessionId}/status/{executionId}/stream` (SSE)
- `GET /api/workflow/{sessionId}/history`

**Logs** (`AppController`):
- `GET /api/logs/{sessionId}` → list filenames
- `GET /api/logs/{sessionId}/{filename}` → parsed JSON
- `GET /api/logs/{sessionId}/{filename}/raw` → raw String

**Sessions** (`SessionController`):
- `GET /api/sessions/{sessionId}`
- `GET /api/sessions/{sessionId}/workflows/{workflowId}`

**Plugins** (`PluginController`):
- `GET /api/plugins` → list all plugins
- `GET /api/plugins/{type}` → detailed plugin info

**Control Bus** (`ControlBusController`):
- `GET /api/control/nodes` → active node IDs
- `GET /api/control/nodes/{nodeId}/heartbeat` → last heartbeat
- `POST /api/control/nodes/{nodeId}/command` → send admin command
- `GET /api/control/stream` (SSE) → all control signals

### MCP Server

**Protocol**: STATELESS, ASYNC, endpoint `/sse`

**Transport**:
- Dev: HTTP (for testing)
- Prod: `spring.ai.mcp.server.stdio: true` → stdio transport (for Claude Code integration)

**13 Tools**: `create_session`, `get_session_details`, `list_sessions`, `trigger_workflow`, `get_workflow_status`, `get_workflow_details`, `stream_session_logs`, `get_workflow_execution_logs`, `list_plugins`, `get_plugin_details`, `get_control_bus_status`, `get_plugin_creation_guide`

**2 Prompts**: `debug-workflow`, `create-session-config`

**3 Resources**:
- `yukta://overview` — general Yukta info
- `yukta://architecture` — this architecture doc
- `yukta://sessions/{sessionId}/logs` — session logs

### Web UI

Located in `ui/src/main/jte/`:

- **Templates**: JTE (Java Templating Engine) pre-compiled to bytecode
- **Styling**: Tailwind CSS 4 (compiled at build time)
- **JavaScript**: Alpine.js + HTMX 2.0.4
- **Pages**:
  - `/ui` — Dashboard (session list, execution history)
  - `/ui/session/{sessionId}` — Session detail
  - `/ui/workflow/{sessionId}/{workflowId}` — Workflow detail with live DAG visualization
  - `/ui/history` — Execution history (filterable)
  - `/ui/control` — ControlBus console (live heartbeats, signals)
- **Live DAG Visualization**: SVG canvas via Alpine.js `dagComponent` in `ui/src/main/resources/static/js/app.js`; supports zoom, pan, node inspection

---

## 4. Security Model

### Input Validation

Annotations prevent injection and path traversal:

| Annotation | Pattern | Prevents |
|-----------|---------|----------|
| `@SessionId` | `^(?!.*\.\.)[ ^/\\]*$` | `../`, `/`, `\` in session ID |
| `@WorkflowId` | Same as SessionId | Traversal in workflow ID |
| `@FileName` | Blocks `<>:"/\|?*` | OS-illegal filename injection |
| `@ProjectPath` | Size-bounded, no traversal | Path traversal in project paths |

### Error Handling

- `GlobalExceptionHandler` returns structured `ApiResponse` errors
- No stack traces in production responses
- Validation errors include field name + message (no sensitive details)

### Plugin-Specific Security

- **PROCESS_EXECUTOR**: Docs warn against exporting secrets in `env` config; use `SecretProvider` for encrypted resolution
  - `useShell=true` escapes arguments properly
  - Timeout enforced (default 3600s)
- **SecretProvider** interface enables encrypted secret resolution (default: no-op)

### Session Isolation

- Each session has its own:
  - Configuration (workflows, project path)
  - Log directory (`/tmp/.yukta/sessions/{sessionId}/`)
  - Execution context (sessionId + executionId namespace)
- Plugins cannot access other sessions' data

---

## 5. Performance

### Reactive Non-Blocking

- Virtual threads enabled (`spring.threads.virtual.enabled: true`)
- Plugins with `isBlocking() = true` run on virtual thread scheduler
- No `.block()` anywhere in production code
- All services return `Mono<T>` or `Flux<T>`

### Concurrency

- `WorkflowService` queues per `sessionId:workflowId` key:
  - Same workflow runs sequentially (prevents race conditions)
  - Different workflows run concurrently
- `TaskTrackerService`: O(1) execution lookup via dual index
- Event batching: 100 events or 50ms to reduce contention
- `ReactiveLock` for non-blocking mutual exclusion in reactive pipelines

### Configuration

```yaml
yukta.session.store-type: in-memory        # or "file"
yukta.session.base-dir: /tmp/.yukta
yukta.session.execution-timeout-seconds: 3600
yukta.tracker.cleanup-ttl: 10m             # TTL before auto-cleanup
control.bus.batch.size: 100                # Batch size for event sink
control.bus.batch.timeout.ms: 50           # Timeout for batch emission
control.bus.buffer.size: 256                # Multicast sink buffer
spring.threads.virtual.enabled: true       # Java virtual threads
spring.ai.mcp.server.protocol: STATELESS
spring.ai.mcp.server.type: ASYNC
spring.ai.mcp.server.stdio: false          # true in production (stdio transport)
```

---

## 6. Built-in Plugins (16 Total)

### Triggers (2)
- `api-trigger` — HTTP POST listener (entry point for REST workflows)
- `CONSTANT_SOURCE` — Emit constant payload on demand (for testing)

### Processors (13)
- `PROCESS_EXECUTOR` — Execute OS commands with timeout, env, streaming
- `BRANCH` — SpEL-based conditional routing (multiple output ports)
- `FILTER` — Pass/reject based on condition
- `MAPPER` — Transform payload using SpEL
- `CONTENT-FILTER` — Filter payload fields
- `ENRICHER` — Add metadata to messages
- `SPLITTER` — Split 1 message into N
- `AGGREGATOR` — Combine N messages into 1
- `RESEQUENCER` — Enforce sequence order
- `RECIPIENT_LIST` — Route to multiple recipients
- `LOOP_PREDICATE` — Retry until condition
- `LOOP_STREAM` — Iterate over collection
- `SUB_WORKFLOW` — Nested workflow execution

### Terminals (1)
- `console` — Log output (debugging)

---

## Key Files

- **Core Services**: `core/src/main/java/com/infenia/yukta/service/`
- **Plugins API**: `plugin-api/src/main/java/com/infenia/yukta/plugin/`
- **Plugin Implementations**: `plugins/src/main/java/com/infenia/yukta/plugins/`
- **Controllers**: `core/src/main/java/com/infenia/yukta/controller/`
- **MCP Tools**: `core/src/main/java/com/infenia/yukta/mcp/`
- **Configuration**: `core/src/main/java/com/infenia/yukta/config/`
- **Validation**: `core/src/main/java/com/infenia/yukta/validation/`
- **UI Templates**: `ui/src/main/jte/`

---

## Tech Stack

- **Java 25** (reactive language features, virtual threads)
- **Spring Boot 4.0.2** (WebFlux for non-blocking I/O)
- **Project Reactor** (Mono/Flux for reactive streams)
- **Gradle 9.0** (multi-module build)
- **GraalVM** (native image support, 50MB executable)
- **JTE** (Java Templating Engine) + **Tailwind CSS 4**
