# Plugins CLAUDE.md

## Overview
Implementations of various Yukta plugins.

## Plugin Types
- **Processors**: Located in `processors/`. Modules: `process-executor`, `internal/internal-core`.
- **Triggers**: Located in `triggers/`. Modules: `api-trigger`, `constant-source`, `auto-trigger`.
- **Terminals**: Located in `terminals/`. Module: `console-terminal`.

Note: `triggers/manual-trigger/` exists on disk but has no source and isn't in `settings.gradle.kts` — it's a retired/unwired module, not a live plugin.

## Build Commands
```bash
# Build a specific plugin
./gradlew :plugins:processors:process-executor:build
```

## Development
Each plugin should implement the interfaces defined in `plugin-api`.

## Imports
@.claude/rules/coding-standards.md
@.claude/rules/git-workflow.md
