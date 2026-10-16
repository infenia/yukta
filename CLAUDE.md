# Yukta Project CLAUDE.md

This is the global repository guide for **Yukta**.

## Project Overview

**Yukta** is a reactive, DAG-based workflow orchestration server: define workflows once (JSON nodes/edges) and run them via REST, a web UI, or an MCP server for AI agents. It ships with built-in Trigger/Processor/Terminal plugins (CI/CD, data pipelines, quality gates, etc.) and is extensible via `plugin-api`. Code-quality-gate enforcement for AI-driven development is one use case, not the whole product.

## Tech Stack

Java 25 (toolchain) · Spring Boot (WebFlux; version in `gradle/libs.versions.toml`) · Gradle 9.0 · Project Reactor · GraalVM native image · Spring AI MCP Server.

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

All commits follow **Conventional Commits** format. See `.gitmessage` template (shown when committing), `.git-commit-guide.md`, and `.claude/rules/git-workflow.md` for full details. The `.git/hooks/commit-msg` hook validates format automatically.

## Testing Strategy
- Unit tests use JUnit 5 + Mockito (for reactive: `reactor.test`).
- Use `StepVerifier` for testing reactive streams.
- Quality gates (Checkstyle, PMD, SpotBugs, OpenGrep) are enforced via `build-logic` — see `.claude/rules/coding-standards.md` for details.
- Minimum coverage thresholds are module-specific.
- Dogfood loop: watch a run with control-bus events, retry a failed stage, and record Yukta defects you notice in `docs/internal/bugs.mdx` (under the module's `### Dogfood findings`) — see the `yukta` skill and `examples/11-dogfood/README.md`.
- The full `check` gate can run as a staged Yukta workflow with a single `RESULT` line: `examples/11-dogfood/scripts/yukta-check.sh [-m <module> | <gradle args>]` (see the `yukta` skill and `examples/11-dogfood/README.md`).
- The staged check **heals itself** by default: a failing stage gets a deterministic fix (`spotlessApply`), then up to two headless Claude Code attempts (fresh, then a fork of your session), then a `verify` pass. While it runs, do not edit files or run Gradle in that checkout. Act only on the final `RESULT` or an `ESCALATE:`, review everything under `healed=` with `git diff` before committing (the fixer never commits), and use `--no-heal` to get the plain fail-fast chain. Details: the `yukta` skill, "Self-healing runs".

### Test Execution Policy (speed)
Waiting on tests is the main development bottleneck. **Run the smallest scope that covers your change**; escalate only when the blast radius justifies it.

| Tier | When | Command |
|---|---|---|
| 0 | Quick compile feedback after an edit | `./gradlew :<mod>:compileJava :<mod>:compileTestJava -q` |
| 1 (default) | Changed a few classes | `./gradlew :<mod>:test --tests '<FQCN>'` (repeat `--tests` per class; `Class.method` while iterating) — tests of the modified classes only |
| 2 | Changed a public contract or many classes in a package | `./gradlew :<mod>:test --tests 'com.infenia.yukta.<package>.*'` |
| 3 | Many files changed across a module, or shared code (`plugin-api`, `messaging`, config, `build-logic`) | `./gradlew :<mod>:test` |
| 4 | Final gate, once, before commit/PR, or cross-module impact | `./gradlew spotlessApply check` |

- Tier 4 is best run as `yukta-check.sh --watch` (the `yukta` skill): same gates, staged, self-healing, one `RESULT` line. Use plain `./gradlew spotlessApply check` when no server is running.
- Never start at Tier 3/4. When you escalate, state the reason in one line (e.g. "14 files changed across orchestrator + store").
- Run `spotlessApply` and `check` **once at the end**, not after every edit.
- Don't use `clean` or `--rerun-tasks`; Gradle's up-to-date/build cache already skips unchanged work.
- Run Tier 3/4 with `run_in_background` and keep working; don't block on them.
- Use `-q --console=plain`; on failure read only the failing test's report in `build/test-results`, not the full log.
- Add `--continue` on multi-module runs to see all failures in one pass; `-PfailFast` to stop at the first failing test; `-Pcoverage` to produce a JaCoCo report on a scoped run.
- Locate tests via `<Class>Test` naming or CodeGraph, not repo-wide grep.

### Test Skill
Load the below skill for test generation and test improvement.
- **Name**: tests
- **Description**: Use for generating unit tests (Java, JUnit 5, Mockito, AssertJ).


## Imports
@.claude/rules/coding-standards.md
@.claude/rules/git-workflow.md
