# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited

# Plugin API Module CLAUDE.md

## Overview
Plugin abstraction interfaces and base classes.

## Build & Development Commands
```bash
# Run tests
./gradlew :plugin-api:test
```

## Plugin Categories
1. **TriggerPlugin**: Initiates workflows.
2. **ProcessorPlugin**: Transforms or validates data.
3. **TerminalPlugin**: Finalizes workflows.

## Key Files
- `com.infenia.yukta.plugin.core.Plugin`: Base interface for all workflow plugins.
- `com.infenia.yukta.plugin.type.TriggerPlugin`, `.type.ProcessorPlugin`, `.type.TerminalPlugin`: Category-specific plugin interfaces.
- `com.infenia.yukta.plugin.core.WorkflowContext`, `.core.PluginCategory`, `.core.PluginDescriptor`: Shared plugin-execution types.
- `com.infenia.yukta.plugin.store`: `NodeCheckpointStore`, `SecretProvider`.
- Module guide (interactive): `docs/plugin-api-module-guide.html`.

## Imports
@.claude/rules/coding-standards.md
@.claude/rules/git-workflow.md

## Testing speed
Follow the tiered "Test Execution Policy (speed)" in the root `CLAUDE.md`: run `--tests` for the classes you changed first; full `:plugin-api:test` only when many files changed.
