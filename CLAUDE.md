# Yukta Project CLAUDE.md

This is the global repository guide for **Yukta**.

## Project Overview

**Yukta** is a reactive, DAG-based workflow orchestration server: define workflows once (JSON nodes/edges) and run them via REST, a web UI, or an MCP server for AI agents. It ships with built-in Trigger/Processor/Terminal plugins (CI/CD, data pipelines, quality gates, etc.) and is extensible via `plugin-api`. Code-quality-gate enforcement for AI-driven development is one use case, not the whole product.

## Tech Stack

Java 25 (toolchain) · Spring Boot 4.1.0 (WebFlux) · Gradle 9.0 · Project Reactor · GraalVM native image · Spring AI MCP Server.

## Repository Roadmap & Layout

This is a multi-module Gradle monorepo. Launch Claude from the specific package folder for efficiency — each module below has its own `CLAUDE.md` with module-specific build commands and architecture notes; this file covers only what's true repo-wide.

- **`boot`**: Spring Boot application entry point and GraalVM native config.
- **`build-logic`**: Gradle build conventions (Java, Quality, JaCoCo).
- **`cli`**: Lightweight Go-based CLI for remote server interaction.
- **`core`**: Core services, DAG orchestration engine, and MCP tools.
- **`mcp`**: MCP server implementation.
- **`messaging`**: Shared messaging abstractions.
- **`plugin-api`**: Abstraction interfaces for Yukta plugins.
- **`plugins`**: Implementations of Processors, Triggers, and Terminals.
- **`ui`**: JTE templates and Tailwind CSS frontend.
- **`web`**: REST controllers and web-layer mapping.

## Global Commands

```bash
# Start the application (Swagger UI at http://localhost:8080/swagger-ui.html)
./gradlew bootRun

# Run all quality checks across all modules (tests, Checkstyle, PMD, SpotBugs, Semgrep, JaCoCo)
./gradlew check

# Run every test in one module
./gradlew :core:test

# Run a single test class
./gradlew :core:test --tests com.infenia.yukta.service.orchestrator.WorkflowOrchestratorTest

# Format code (must do before commit)
./gradlew spotlessApply

# Clean build the entire project
./gradlew clean build
```

## Testing Strategy
- Unit tests use JUnit 5 + Mockito (for reactive: `reactor.test`).
- Use `StepVerifier` for testing reactive streams.
- Quality gates (Checkstyle, PMD, SpotBugs, Semgrep) are enforced via `build-logic` — see `.claude/rules/coding-standards.md` for details.
- Minimum coverage thresholds are module-specific.

### Test Skill
Load the below skill for test generation and test improvement.
- **Name**: tests
- **Description**: Use for generating unit tests (Java, JUnit 5, Mockito, AssertJ).

## GitNexus — Code Intelligence
This project is indexed by GitNexus. Use GitNexus MCP tools for impact analysis and navigation.
- **Impact Analysis**: Run `impact` before editing any symbol.
- **Verification**: Run `detect_changes()` before committing.

## Imports
@.claude/rules/coding-standards.md
@.claude/rules/git-workflow.md
