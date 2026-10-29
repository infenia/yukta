# Boot Module CLAUDE.md

## Overview
Spring Boot application entry point and application-level configuration.

## Build & Development Commands
```bash
# Start the application
./gradlew bootRun

# Build native image (GraalVM)
yk gradle nativeCompile
```

## Configuration
- `src/main/resources/application.yaml`: Application configuration.
- `../docs/contributing/modules/boot.mdx` (module guide); open risks, bugs and review findings: GitHub issues labelled `boot` (`gh issue list -l boot`; risks also carry `risk`); evolution path and quality lenses: `../design/assessments/boot.md`.
- Optional plugins (see `build.gradle.kts`), one of: `./gradlew bootRun -Pyukta.plugins=none`, `./gradlew bootRun -Pyukta.plugins=all`, or `./gradlew bootRun -Pyukta.plugins=http-request,script-js`.

- Native-image metadata: `src/main/resources/META-INF/native-image/com.infenia.yukta/boot/reflect-config.json` is hand-kept. Prefer `@RegisterReflectionForBinding` / a `RuntimeHintsRegistrar`; to regenerate, run the tests on a GraalVM JVM with `-agentlib:native-image-agent=config-merge-dir=<that dir>` and review the diff (steps in `../docs/contributing/modules/boot.mdx`).
- Native build heap: `-Pnative.heap=<size>` (default 16g, see `build.gradle.kts`).

## Tech Stack
- **Spring Boot 4.1.0**
- **GraalVM**

## Imports
@.claude/rules/coding-standards.md
@.claude/rules/git-workflow.md
