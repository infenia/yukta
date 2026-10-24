# Messaging Module CLAUDE.md

## Overview
Shared messaging abstractions and implementations.

## Architecture
Message-based inter-node communication framework:
- **`Message`**: Generic interface for an atomic data packet (header + payload).
- **`NodeMessageChannel`**: Transport abstraction between workflow nodes — `DirectNodeMessageChannel` (in-memory pass-through) is the only implementation. Register your own `NodeMessageChannelProvider` bean to override it.

## Key Files
- `src/main/java/com/infenia/yukta/message/Message.java`
- `src/main/java/com/infenia/yukta/message/channel/NodeMessageChannel.java` (+ `DirectNodeMessageChannel`)
- Module guide: `../docs/contributing/modules/messaging.mdx` (module guide); open risks, bugs and review findings: GitHub issues labelled `messaging` (`gh issue list -l messaging`; risks also carry `risk`); evolution path and quality lenses: `../design/assessments/messaging.md`

## Build & Development Commands
```bash
# Run messaging tests
yk gradle :messaging:test
```

## Imports
@.claude/rules/coding-standards.md
@.claude/rules/git-workflow.md

## Testing speed
Follow the tiered "Test Execution Policy (speed)" in the root `CLAUDE.md`: run `--tests` for the classes you changed first; full `:messaging:test` only when many files changed.
