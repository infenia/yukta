# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited

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

# Run all quality checks across all modules (tests, Checkstyle, PMD, SpotBugs, OpenGrep, JaCoCo)
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

## Commit Message Conventions

All commits to Yukta follow **Conventional Commits** format:

```
<type>(<scope>): <subject>

<body>

<footer>
```

**Quick reference:**
- **Types**: `feat`, `fix`, `docs`, `test`, `refactor`, `chore`, `style`, `perf`, `ci`, `build`
- **Scope** (optional): `cli`, `core`, `web`, `ui`, `plugin-api`, `plugins`, `mcp`, `messaging`, `boot`, `build-logic`, `deps`
- **Subject**: Imperative mood, max 50 chars, no period, lowercase start
- **Body**: Detailed explanation of what and why (wrap at 72 chars)
- **Footer**: Issue references (`Fixes #123`), breaking changes, etc.
- **NO Co-Authored-By**: Remove from all manual commits

**Example:**
```
feat(cli): add workflow status command

Add a new status subcommand to fetch current workflow execution state
with human-readable formatting showing node progress and errors.

Fixes #142
```

See `.gitmessage` template (shown when committing) and `.git-commit-guide.md` for full details. The `.git/hooks/commit-msg` hook validates format automatically.

## Testing Strategy
- Unit tests use JUnit 5 + Mockito (for reactive: `reactor.test`).
- Use `StepVerifier` for testing reactive streams.
- Quality gates (Checkstyle, PMD, SpotBugs, OpenGrep) are enforced via `build-logic` — see `.claude/rules/coding-standards.md` for details.
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
