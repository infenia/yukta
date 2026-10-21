# Plugins CLAUDE.md

## Overview
Implementations of various Yukta plugins.

## Plugin Types
- **Processors**: Located in `processors/`. Modules: `process-executor`, `internal/internal-core`, `http-request` (`HTTP_REQUEST`), `file-io` (`FILE_READ`/`FILE_WRITE`, sandboxed to `baseDir`), `sql-query` (`SQL_QUERY`, parameterized JDBC; driver supplied by deployer), internal-core also has `DELAY`, `WAIT_FOR_SIGNAL` (waits for `signal_node` / REST signal; a signal that comes first is kept for the wait; open waits are listed at `GET /api/sessions/{s}/approvals`), `SUB_WORKFLOW` (runs a workflow of the same session through `SubWorkflowGateway`, results from its terminals), `VALIDATE`, `AGGREGATE`, `KV_STORE` (in-memory, capped; with `yukta.kv.store-type=postgres` in the `kv_entry` table, counters incremented in one statement), `llm-call` (`LLM_CALL`, Spring AI: OpenAI-compatible/Anthropic/Ollama), `session-memory` (`SESSION_MEMORY`, chat history; in memory, or one JSON file per session under `yukta.session-memory.dir`, or in PostgreSQL with `yukta.session-memory.store-type=postgres` (table `session_history`; `yukta.session-memory.ttl` deletes sessions not written for that long) so it survives a restart).  `mcp-tool-call` (`MCP_LIST_TOOLS` lists a server's tools for the `tools` option of `LLM_CALL`, which returns the model's native tool calls in `actions`; `MCP_TOOL_CALL`, MCP client over streamable HTTP; `allowedTools` refuses any other tool, e.g. one picked by a model (`requireApproval` lists tools, or `*`, that wait for a `signal_node` answer first (`approvalSignal`, `approvalTimeoutSeconds`); `LLM_CALL` can refuse prompts and answers with `yukta.llm.deny-input-patterns`, `deny-output-patterns` and `max-input-chars`, space calls per provider with `yukta.llm.rate-limit.<provider>` (requests per minute), end the whole run with `budget.hardStop`, and send earlier tool exchanges (`actions` on an assistant turn, `{role: tool, toolCallId, toolName, content}` turns); gateway-level `yukta.mcp-client.allowed-tools`, `max-argument-bytes` and `deny-argument-patterns` apply to every node), and a `WAIT_FOR_SIGNAL` node in front adds a human approval; session cache, SSE progress heartbeat, cancel, idempotency `_meta`). Sample chat-agent workflow: `bruno/SessionConfigController/Apply Chat Agent Session.bru`.
- **Decision processors** (`processors/decision`, optional plugin `decision`): constrained model decisions for routing and gating, with Ollama System One (`backend: SYSTEMONE`, default, calibrated probabilities, local) or any chat model (`backend: LLM` + `provider` openai/anthropic/ollama, via `llm-call`'s `LlmGateway`; probabilities are the model's own estimate, uncalibrated). Types: `CLASSIFY` (labels, optional `routeByLabel`, `uncertain` port), `GATE` (yes/no, `pass`/`reject`), `SCORE` (levels, optional `high`/`low`), `ASK` (up to 64 questions in one call), `ANOMALY` and `DEDUPE` (compare with the last N items of the node's run; `normal`/`anomalous`, `unique`/`duplicate`). Results go in metadata under `into`. A model failure fails the message; use the node's `engine` policy (`retry`, `onError`) for retries and dead letters. Enabling it also pulls in `LLM_CALL`. System One needs Ollama 0.35+ and a decision model such as `tev1:0.8b`; keep descriptions of labels/levels explicit, a small model needs them.
- **Triggers**: Located in `triggers/`. Modules: `api-trigger`, `constant-source`, `schedule-trigger` (`SCHEDULE_TRIGGER`, interval/cron).
- **Terminals**: Located in `terminals/`. Modules: `console-terminal`, `notify-terminal` (`NOTIFY_TERMINAL`, Slack-compatible JSON webhook).
- **Supervisors**: Located in `supervisors/`. Module: `rule-supervisor` (declarative JSON/SpEL rules implementing `ExecutionSupervisor`; enabled by `yukta.supervisor.rules.file`). Example rule file: `supervisors/rule-supervisor/rules.example.json`.

- **Control planes**: Located in `control-planes/`. Implement `ControlPlanePlugin` (one `decide(event)` per event, hosted like a supervisor). Modules: `rule-control-plane` (the `rule-supervisor` rules behind that contract; enabled by `yukta.control-plane.rules.file`); `model-control-plane` (asks a `DecisionModel`, Ollama System One or any chat model, what to do about each event; enabled by `yukta.control-plane.model.name`). Its prompt is plugin configuration (`ModelPlaneSettings`): `instructions` (the question), `options.<key>.description` and `options.<key>.command` (`NONE`, `PAUSE` or `STOP`; default `continue`=NONE and `pause`=PAUSE, so it cannot stop unless configured), `events` (kinds of event it is asked about, default node and workflow status), plus `backend` (`SYSTEMONE`|`LLM`), `provider`, `base-url`, `api-key`, `timeout` (2s), `min-probability` (0.6). Write option descriptions for your situation: explicit, specific options work far better with a small model than the defaults. Changing them needs a restart. Fails open. `rules-first-control-plane` (rules decide; the model is asked only when no rule fired; rule deny verdicts stay authoritative; it builds both plugins itself from `yukta.control-plane.rules-first.rules-file` and `yukta.control-plane.rules-first.model.*` (same keys as above), so leave the individual plugins' properties unset). The model plugins pull in `processors/decision`.

Note: `triggers/manual-trigger/` exists on disk but has no source and isn't in `settings.gradle.kts` — it's a retired/unwired module, not a live plugin.

## Config schema
Declare `getConfigSchema()` (see `ConfigField`) on a plugin to get a generated config form in the UI. Every plugin that takes configuration declares one (`API_TRIGGER` and `CONSOLE_TERMINAL` take none); a new plugin without a schema falls back to the JSON editor and the usage pattern. Keep field names equal to the keys the plugin reads, and add a test pinning the names and which are required.

## Build Commands
```bash
# Build a specific plugin
./gradlew :plugins:processors:process-executor:build
```

## Development
Each plugin should implement the interfaces defined in `plugin-api`.
Guide: `../docs/contributing/modules/plugins.mdx` (module guide, published in the docs site); open risks, bugs and review findings: GitHub issues labelled `plugins` (`gh issue list -l plugins`; risks also carry `risk`); evolution path and quality lenses: `../design/assessments/plugins.md`.

## Imports
@.claude/rules/coding-standards.md
@.claude/rules/git-workflow.md

## Testing speed
Follow the tiered "Test Execution Policy (speed)" in the root `CLAUDE.md`: run `--tests` for the classes you changed first; full `:plugins:test` only when many files changed.
