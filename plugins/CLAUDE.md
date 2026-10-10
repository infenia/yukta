# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited

# Plugins CLAUDE.md

## Overview
Implementations of various Yukta plugins.

## Plugin Types
- **Processors**: Located in `processors/`. Modules: `process-executor`, `internal/internal-core`, `http-request` (`HTTP_REQUEST`), `llm-call` (`LLM_CALL`, Spring AI: OpenAI-compatible/Anthropic/Ollama), `session-memory` (`SESSION_MEMORY`, in-memory chat history). Sample chat-agent workflow: `bruno/SessionConfigController/Apply Chat Agent Session.bru`.
- **Triggers**: Located in `triggers/`. Modules: `api-trigger`, `constant-source`, `oneshot-trigger`, `schedule-trigger` (`SCHEDULE_TRIGGER`, interval/cron).
- **Terminals**: Located in `terminals/`. Module: `console-terminal`.

Note: `triggers/manual-trigger/` exists on disk but has no source and isn't in `settings.gradle.kts` — it's a retired/unwired module, not a live plugin.

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
