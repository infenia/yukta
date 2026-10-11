# Boot Module CLAUDE.md

## Overview
Spring Boot application entry point and application-level configuration.

## Build & Development Commands
```bash
# Start the application
./gradlew bootRun

# Build native image (GraalVM)
./gradlew nativeCompile
```

## Configuration
- `src/main/resources/application.yaml`: Application configuration.
- `docs/boot-module-guide.html`: interactive guide, including the resolved review findings.
- Optional plugins (see `build.gradle.kts`), one of: `./gradlew bootRun -Pyukta.plugins=none`, `./gradlew bootRun -Pyukta.plugins=all`, or `./gradlew bootRun -Pyukta.plugins=http-request,script-js`.

## Tech Stack
- **Spring Boot 4.1.0**
- **GraalVM**

## Imports
@.claude/rules/coding-standards.md
@.claude/rules/git-workflow.md
