# Plugin API Module CLAUDE.md

## Overview
Plugin abstraction interfaces and base classes.

## Build & Development Commands
```bash
# Run tests
yk gradle :plugin-api:test
```

## Plugin Categories
1. **TriggerPlugin**: Initiates workflows.
2. **ProcessorPlugin**: Transforms or validates data.
3. **TerminalPlugin**: Finalizes workflows.

## Key Files
- `com.infenia.yukta.plugin.core.Plugin`: Base interface for all workflow plugins.
- `com.infenia.yukta.plugin.type.TriggerPlugin`, `.type.ProcessorPlugin`, `.type.TerminalPlugin`: Category-specific plugin interfaces.
- `com.infenia.yukta.plugin.core.WorkflowContext`, `.core.PluginCategory`, `.core.PluginDescriptor`: Shared plugin-execution types.
- `com.infenia.yukta.plugin.core.ConfigField` and `Plugin#getConfigSchema()`: optional per-plugin config parameters (name, type, required, default, options) that the UI renders as a form. Default is empty, in which case the UI shows a raw JSON editor. Keep names in step with the keys the plugin reads from `config`.
- `com.infenia.yukta.plugin.core.PluginLogSink`: live log lines. A plugin takes it from the Reactor context (`WorkflowContext.LOG_SINK_KEY`, absent outside a run) and calls `line("stdout"|"stderr", text)` as output appears; the engine writes each line whole to the execution log (split only above 16,000 chars), counts it as node activity, and then does not echo the node's output message again.
- `com.infenia.yukta.plugin.store`: `NodeCheckpointStore`, `SecretProvider`.
- Module guide: `../docs/contributing/modules/plugin-api.mdx` (module guide); open risks, bugs and review findings: GitHub issues labelled `plugin-api` (`gh issue list -l plugin-api`; risks also carry `risk`); evolution path and quality lenses: `../design/assessments/plugin-api.md`.

## Imports
@.claude/rules/coding-standards.md
@.claude/rules/git-workflow.md

## Testing speed
Follow the tiered "Test Execution Policy (speed)" in the root `CLAUDE.md`: run `--tests` for the classes you changed first; full `:plugin-api:test` only when many files changed.
