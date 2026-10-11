# Plugins CLAUDE.md

## Overview
Implementations of various Yukta plugins.

## Plugin Types
- **Processors**: Located in `processors/`. Modules: `process-executor`, `internal/internal-core`, `http-request` (`HTTP_REQUEST`), `file-io` (`FILE_READ`/`FILE_WRITE`, sandboxed to `baseDir`), `sql-query` (`SQL_QUERY`, parameterized JDBC; driver supplied by deployer), internal-core also has `DELAY`, `VALIDATE`, `AGGREGATE`, `KV_STORE` (in-memory, capped), `llm-call` (`LLM_CALL`, Spring AI: OpenAI-compatible/Anthropic/Ollama), `session-memory` (`SESSION_MEMORY`, in-memory chat history). Sample chat-agent workflow: `bruno/SessionConfigController/Apply Chat Agent Session.bru`.
- **Triggers**: Located in `triggers/`. Modules: `api-trigger`, `constant-source`, `oneshot-trigger`, `schedule-trigger` (`SCHEDULE_TRIGGER`, interval/cron).
- **Terminals**: Located in `terminals/`. Modules: `console-terminal`, `notify-terminal` (`NOTIFY_TERMINAL`, Slack-compatible JSON webhook).
- **Supervisors**: Located in `supervisors/`. Module: `rule-supervisor` (declarative JSON/SpEL rules implementing `ExecutionSupervisor`; enabled by `yukta.supervisor.rules.file`). Example rule file: `supervisors/rule-supervisor/rules.example.json`.

- **Control planes**: Located in `control-planes/`. Implement `ControlPlanePlugin` (one `decide(event)` per event, hosted like a supervisor). Module: `rule-control-plane` (the `rule-supervisor` rules behind that contract; enabled by `yukta.control-plane.rules.file`), `systemone-control-plane` (asks a local Ollama System One model, `POST /v1/systemone`, to continue or pause on node and workflow status events, never stop; enabled by `yukta.control-plane.systemone.model`, plus optional `base-url`, `min-probability` (0.6, the chosen option's probability), `timeout` (PT2S); fails open), `rules-first-control-plane` (rules decide; the model is asked only when no rule fired; rule deny verdicts stay authoritative; it builds both plugins itself from `yukta.control-plane.rules-first.{rules-file,model,base-url,min-probability,timeout}`, so leave the individual plugins' properties unset).

Note: `triggers/manual-trigger/` exists on disk but has no source and isn't in `settings.gradle.kts` — it's a retired/unwired module, not a live plugin.

## Config schema
Declare `getConfigSchema()` (see `ConfigField`) on a plugin to get a generated config form in the UI. Every plugin that takes configuration declares one (`api-trigger`, `ONE_SHOT` and `CONSOLE_TERMINAL` take none); a new plugin without a schema falls back to the JSON editor and the usage pattern. Keep field names equal to the keys the plugin reads, and add a test pinning the names and which are required.

## Build Commands
```bash
# Build a specific plugin
./gradlew :plugins:processors:process-executor:build
```

## Development
Each plugin should implement the interfaces defined in `plugin-api`.
Guide (interactive): `docs/plugins-module-guide.html`; review findings: `docs/plugins-improvements.md`.

## Imports
@.claude/rules/coding-standards.md
@.claude/rules/git-workflow.md

## Testing speed
Follow the tiered "Test Execution Policy (speed)" in the root `CLAUDE.md`: run `--tests` for the classes you changed first; full `:plugins:test` only when many files changed.
