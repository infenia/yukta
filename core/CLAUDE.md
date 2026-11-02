# Core Module CLAUDE.md

## Overview
Core service layer and workflow orchestration engine.

## Build & Development Commands
```bash
# Run tests for core module
yk gradle :core:test

# Run a single test class
yk gradle :core:test --tests com.infenia.yukta.service.orchestrator.WorkflowOrchestratorTest
```

## Key Architecture & Patterns

### Reactive Streams (Core Pattern)
All service logic uses **Project Reactor** (`Mono`, `Flux`).
- Services use `Mono` for single operations, `Flux` for streams.
- Avoid blocking operations.

### Workflow Orchestration
- `com.infenia.yukta.service.orchestrator.WorkflowOrchestrator`: DAG orchestration logic, split across `assembly/`, `compiler/`, `preparator/`, `strategy/`, `stream/`, `tracker/`, and `validator/` subpackages.
- `tracker/` keeps the queue and the lookup maps in `DefaultTaskTrackerService`, one run's state in
  `WorkflowState`, the spans and meters in `RunTelemetry`, applying queued events in
  `TrackerEventApplier`, the live log and status streams in `ExecutionStreams` and the remembered
  finished runs in `ExecutionHistory`. `DefaultControlBusGateway` delegates
  starting (admission, shared prepare, idempotency) to `WorkflowStarter`. It also delegates node commands to
  `NodeCommands`, restarts to `RestartCoordinator` and read-only views to `ExecutionQueries`
  (`ControlDispatch` is their shared base).
- `com.infenia.yukta.service.plugin.PluginRegistry`: Plugin registration.
- Executes a Directed Acyclic Graph (DAG) of nodes and edges.

### Immutability & Records
Use **Java records** for data models (preferred) or **Lombok** (`@RequiredArgsConstructor`, `@Getter`) for immutable objects.

### Validation
- Custom validators in `com.infenia.yukta.validation`.
- Use Jakarta Bean Validation.

### Logging API Design
- **PluginLoggerFactory**: Uses `@FunctionalInterface` with a single abstract method `create(LoggerContext)`.
  - `LoggerContext` is a record encapsulating execution context (executionId, sessionId, pluginId, pluginName).
  - Convenience default methods provided for 3 and 4-parameter creation to maintain backward compatibility.
  - Implementation: `DefaultPluginLoggerFactory` extracts values from LoggerContext and passes to `DefaultPluginLogger`.

## Reference rules (load on demand)
The detailed reference that used to live here is in path-scoped rules: each loads when you read, write or edit a matching file (a rule's
`paths` are relative to the folder that holds its `.claude`). To read one without touching its files, open it.

| Topic | Rule | Loads for |
|---|---|---|
| Node `engine` policy (retry, `onError`, `trigger`, timeouts, `edgeBuffer`), node status, node timeouts, breakpoints and tracing | `core/.claude/rules/engine-node-policy.md` | `service/orchestrator/**`, `model/workflow/**`, `service/control/valve/**` |
| Restart archive, checkpoints, resume, execution log, Plus split, feature gates | `core/.claude/rules/restart-and-execution-log.md` | `service/archive`, `service/log`, `service/store`, `ExecutionResumer`, `config/**` |
| Scheduler limits, idempotent starts, control audit, stop modes, gateway commands, signals and approvals | `core/.claude/rules/execution-limits-and-control.md` | `service/control/**`, `model/control/**` |
| Definition stores and versions | `core/.claude/rules/definitions-and-versions.md` | `service/workflow/**`, `service/session/**`, `model/session/**` |
| Remote workers, high availability (leader lease) | `core/.claude/rules/workers-and-ha.md` | `service/worker/**`, `service/leader/**` |
| Supervisors, `ControlReviewer`, the event feed | `core/.claude/rules/supervisors.md` | `service/supervisor/**`, `service/control/directive/**` |
| Input requests, `InputBroker`, redaction | `core/.claude/rules/input-requests.md` | `service/input/**`, `service/redact/**` |
| Tracker queue, execution results, validation limits, metrics | `core/.claude/rules/tracker-results-and-metrics.md` | `service/orchestrator/tracker`, `.../validator`, `service/result`, `service/streaming` |
| PostgreSQL stores | `plus/.claude/rules/postgres-stores.md` | `plus/persistence-postgres/**` |
| `SUB_WORKFLOW` | `plugins/.claude/rules/sub-workflows.md` | `plugins/processors/internal/**` |
| LLM and MCP-tool budgets | `plugins/.claude/rules/llm-budgets.md` | `llm-call`, `mcp-tool-call` |
| `${env.X}`, expression safety, script and process limits | `plugin-api/.claude/rules/expressions-and-environment.md` | `plugin-api/src/main/java/**/util/**` |

## Key File Locations
- **Services**: `src/main/java/com/infenia/yukta/service/`
- **Validation**: `src/main/java/com/infenia/yukta/validation/`
- **Module reference (architecture, feature catalog, settings, metrics)**: `../docs/contributing/core-reference.mdx`. When you add, change or remove a core feature, setting or meter, update it and run `core/scripts/check-core-reference.sh` (then `--stamp`); the page's "Maintaining this document" section says what to edit.
- **Module guide (components, scenarios, ADRs)**: `../docs/contributing/modules/core.mdx`; open risks, bugs and features: GitHub issues labelled `core` (`gh issue list -l core`); assessment and plans: `../design/assessments/core.md`, `../design/plans/`

## Testing speed
Follow the tiered "Test Execution Policy (speed)" in the root `CLAUDE.md`: run `--tests` for the classes you changed first; full `:core:test` only when many files changed.
