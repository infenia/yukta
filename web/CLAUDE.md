# Web Module CLAUDE.md

## Overview
Web layer with REST controllers and UI support.

## Build & Development Commands
```bash
# Run web tests
./gradlew :web:test
```

## Patterns
- Controllers return `Mono<ResponseEntity<T>>` or `Mono<T>`.
- **MapStruct**: Used for DTO mapping (`AppConfigMapper`).

## Key Files
- **Controllers**: `src/main/java/com/infenia/yukta/controller/`
- **Mappers**: `src/main/java/com/infenia/yukta/mapper/`

## Imports
@.claude/rules/coding-standards.md
@.claude/rules/git-workflow.md
