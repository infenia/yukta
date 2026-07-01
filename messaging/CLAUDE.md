# Messaging Module CLAUDE.md

## Overview
Shared messaging abstractions and implementations.

## Architecture
Message-based inter-node communication framework with optional persistence:
- **`Message`**: Generic interface for an atomic data packet (header + payload).
- **`NodeMessageChannel`**: Transport abstraction between workflow nodes — `DirectNodeMessageChannel` (in-memory) or `PersistingNodeMessageChannel` (persisted).
- **`MessageStore`**: Central repository for message auditing/history reconstruction.

## Key Files
- `src/main/java/com/infenia/yukta/message/Message.java`
- `src/main/java/com/infenia/yukta/message/channel/NodeMessageChannel.java` (+ `DirectNodeMessageChannel`, `PersistingNodeMessageChannel`)
- `src/main/java/com/infenia/yukta/message/store/MessageStore.java`

## Build & Development Commands
```bash
# Run messaging tests
./gradlew :messaging:test
```

## Imports
@.claude/rules/coding-standards.md
@.claude/rules/git-workflow.md
