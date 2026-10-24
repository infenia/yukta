# Yukta Project CLAUDE.md

This is the global repository guide for **Yukta**.

## Project Overview

**Yukta** is a reactive, DAG-based workflow orchestration server: define workflows once (JSON nodes/edges) and run them via REST, a web UI, or an MCP server for AI agents. It ships with built-in Trigger/Processor/Terminal plugins (CI/CD, data pipelines, quality gates, etc.) and is extensible via `plugin-api`. Code-quality-gate enforcement for AI-driven development is one use case, not the whole product.

## Tech Stack

Java 25 (toolchain) · Spring Boot (WebFlux; version in `gradle/libs.versions.toml`) · Gradle 9.8 (wrapper) · Project Reactor · GraalVM native image · Spring AI MCP Server.

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

Builds, tests and checks run **through Yukta**, never `./gradlew` directly (a hook blocks it and names the
command to use). `yk` is `examples/11-dogfood/yk` (on PATH in Claude Code sessions); it runs every Gradle
call as a Yukta workflow on the **dev server: built from `origin/main`, on `http://127.0.0.1:9858`** (web UI,
REST, MCP at `/mcp`), started on demand. See the `yukta` skill.

```bash
yk check                    # all quality gates, staged and self-healing (tests, Checkstyle, PMD, SpotBugs, OpenGrep, JaCoCo, CLI)
yk check -m core            # the same gates for one module (repeat -m)
yk gradle :core:test --tests com.infenia.yukta.service.orchestrator.WorkflowOrchestratorTest
yk gradle spotlessApply     # format (before commit)
yk status | up | down       # the dev server

# Run the application you are developing (not a build: stays direct; Swagger UI at http://localhost:8080/swagger-ui.html)
./gradlew bootRun
```

## Commit Message Conventions

All commits follow **Conventional Commits** format. See `.gitmessage` template (shown when committing), `.git-commit-guide.md`, and `.claude/rules/git-workflow.md` for full details. The `.git/hooks/commit-msg` hook validates format automatically.

## Testing Strategy
- Unit tests use JUnit 5 + Mockito (for reactive: `reactor.test`).
- Use `StepVerifier` for testing reactive streams.
- Quality gates (Checkstyle, PMD, SpotBugs, OpenGrep) are enforced via `build-logic` — see `.claude/rules/coding-standards.md` for details.
- Minimum coverage thresholds are module-specific.
- Dogfood loop: watch a run, retry a failed stage, and file each Yukta defect you notice as a `dogfood` GitHub issue (`yk finding`) — see the `yukta` skill and `examples/11-dogfood/README.md`.
- `yk check` **heals itself** by default: a failing stage gets a deterministic fix (`spotlessApply`), then up to two headless Claude Code attempts, then a `verify` pass. While it runs, do not edit files or run Gradle in that checkout. Act only on the final `RESULT` or an `ESCALATE:`, review everything under `healed=` with `git diff` before committing (the fixer never commits), and use `--no-heal` for the plain fail-fast chain.

### Test Execution Policy (speed)
Waiting on tests is the main development bottleneck. **Run the smallest scope that covers your change**; escalate only when the blast radius justifies it.

| Tier | When | Command |
|---|---|---|
| 0 | Quick compile feedback after an edit | `yk gradle :<mod>:compileJava :<mod>:compileTestJava` |
| 1 (default) | Changed a few classes | `yk gradle :<mod>:test --tests '<FQCN>'` (repeat `--tests` per class; `Class.method` while iterating) — tests of the modified classes only |
| 2 | Changed a public contract or many classes in a package | `yk gradle :<mod>:test --tests 'com.infenia.yukta.<package>.*'` |
| 3 | Many files changed across a module, or shared code (`plugin-api`, `messaging`, config, `build-logic`) | `yk check -m <mod>` |
| 4 | Final gate, once, before commit/PR, or cross-module impact | `yk check` |

- Every run ends with one `RESULT status=… failed_node=… healed=…` line; read that and the `ERROR` lines before it, not logs. A Stop hook asks for `yk check -m <changed modules>` when the session changed code (`YUKTA_DOGFOOD=off` opts out).
- Never start at Tier 3/4. When you escalate, state the reason in one line (e.g. "14 files changed across orchestrator + store").
- Run `spotlessApply` and `check` **once at the end**, not after every edit.
- Don't use `clean` or `--rerun-tasks`; Gradle's up-to-date/build cache already skips unchanged work.
- Run Tier 3/4 with `run_in_background` (or `--detach` + the `yukta-run-watcher` subagent) and keep working; don't block on them.
- On failure read only the failing test's report in `build/test-results`, not the full log.
- Add `--continue` on multi-module runs to see all failures in one pass; `-PfailFast` to stop at the first failing test; `-Pcoverage` to produce a JaCoCo report on a scoped run.
- Locate tests via `<Class>Test` naming or CodeGraph, not repo-wide grep.

### Test Skill
Load the below skill for test generation and test improvement.
- **Name**: tests
- **Description**: Use for generating unit tests (Java, JUnit 5, Mockito, AssertJ).


## Picking Up Assigned Issues

When asked to work on "my issues" / "assigned issues", list them yourself; never ask for numbers:

```bash
gh issue list -R infenia/yukta --assignee @me --state open --json number,title,labels,url
gh project item-list 5 --owner infenia --limit 700 --format json \
  -q '.items[] | select(.status=="Ready") | [.content.number, .priority, .module, .title] | @tsv'
```

Take issues that are both assigned to the user and in Status **Ready**, highest Priority first. An
issue not in Ready lacks acceptance criteria: report it and ask rather than start. Open the PR with
`Fixes #<n>` and paste the `yk check` RESULT line into its description.

## Imports
@.claude/rules/coding-standards.md
@.claude/rules/git-workflow.md

IMPORTANT: When applicable, prefer using intellij-index MCP tools for code navigation and refactoring.
