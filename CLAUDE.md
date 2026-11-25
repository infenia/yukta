# Yukta Project CLAUDE.md

This is the global repository guide for **Yukta**.

## Project Overview

**Yukta** is a reactive, DAG-based workflow orchestration server: define workflows once (JSON nodes/edges) and run them via REST, a web UI, or an MCP server for AI agents. It ships with built-in Trigger/Processor/Terminal plugins (CI/CD, data pipelines, quality gates, etc.) and is extensible via `plugin-api`. Code-quality-gate enforcement for AI-driven development is one use case, not the whole product.

## Tech Stack

Java 25 (toolchain) · Spring Boot (WebFlux; version in `gradle/libs.versions.toml`) · Gradle 9.8 (wrapper) · Project Reactor · GraalVM native image · Spring AI MCP Server.

## Repository Roadmap & Layout

This is a multi-module Gradle monorepo. Launch Claude from the specific package folder for efficiency — each module below has its own `CLAUDE.md` with module-specific build commands and architecture notes; this file covers only what's true repo-wide.

- **`architecture`**: test-only ArchUnit rules (module layering, plugin independence, coding conventions); `plus/plus-architecture` does the same for Plus. See `.claude/rules/architecture.md`.
- **`boot`**: Spring Boot application entry point and GraalVM native config.
- **`build-logic`**: Gradle build conventions (Java, Quality, JaCoCo).
- **`cli`**: Lightweight Go-based CLI for remote server interaction.
- **`core`**: Core services, DAG orchestration engine, and MCP tools.
- **`mcp`**: MCP server implementation.
- **`messaging`**: Shared messaging abstractions.
- **`plugin-api`**: Abstraction interfaces for Yukta plugins.
- **`plugins`**: Implementations of Processors, Triggers, and Terminals.
- **`ui`**: React SPA (Vite, Tailwind CSS) built into a static JAR for `boot`.
- **`web`**: REST controllers and web-layer mapping.

## Global Commands

Builds, tests and lint (Gradle, Go, pnpm/npx) run **through Yukta**, never `./gradlew` directly (the `yukta-dogfood` mod blocks it and names the command to
use). `yk` comes from the separate [yukta-dogfood](https://github.com/infenia/yukta-dogfood) repository: run `scripts/dogfood-setup.sh` once (it clones the ref in `.dogfood-ref`, `main` (the latest dogfood), into `~/.local/state/yukta-dev/dogfood` and moves an old checkout to it; on PATH in Claude Code sessions after that); `yk gradle` and `yk tool` run every call as
the `tool` Yukta workflow on the **dev server: built from `origin/main`, on `http://127.0.0.1:9858`**, started
on demand. See the `yukta` skill.

```bash
yk gradle :core:compileJava :core:compileTestJava
yk gradle :core:test --tests com.infenia.yukta.service.orchestrator.WorkflowOrchestratorTest
yk gradle spotlessApply     # format (before commit)
yk gradle :architecture:test :plus:plus-architecture:test   # the ArchUnit architecture rules
yk check                    # the scoped, self-healing gate for your changes (yk check --full before a PR); yk plan shows what it would run
yk tool -C cli go test ./...                      # Go (cli/)
yk tool -C ui npx --no-install prettier --check .  # UI (ui/): pnpm/npx/node_modules/.bin commands
# yk, heal, plan, hooks (TypeScript on Bun) are tested in the yukta-dogfood repository, not here

# Run the application you are developing (not a build: stays direct; Swagger UI at http://localhost:9858/swagger-ui.html)
./gradlew bootRun
```

Every run ends with one `RESULT status=… failed_node=…` line; read that and the `ERROR` lines before it, not logs.
Run the smallest scope that covers your change (a test class, then a package, then a module's tasks).

> Beyond the build commands, `yk` also offers `up|status|down`, `stats`, `heal report`, `report`, `throttle` and `ticket` (see the `yukta`
> skill and the yukta-dogfood README). The older `yk run issue|epic` commands and slash commands are switched off there while the loop is rebuilt.

## GitHub API budget

The GraphQL quota is shared per user by every Claude session and tool on the machine. `gh project item-list` pages the whole board and is the
heaviest call.

- Check `gh api rate_limit --jq .resources.graphql` before a batch of project-board calls. Never call `gh project item-list` in a loop or only to verify one item.
- On "API rate limit exceeded": do not retry. Report the `reset` time (`date -d @<reset>`) and wait.
- Prefer REST (`gh issue list`, `gh issue view`, `gh pr list`) when it answers the question.

## Files Claude cannot Read

Checked-in generated or vendored files that would fill the context are denied to Claude's `Read` (and to `cat`/`grep`/`head` on them):

| Pattern | What | Use instead |
|---|---|---|
| `**/pnpm-lock.yaml` | UI lockfile (210 KB) | `pnpm why`, `pnpm list` via `yk tool -C ui` |
| `**/openapi.json` | API snapshots `ui/openapi.json`, `docs/api/openapi.json` (200 KB each) | the controllers; `docs/api/*.mdx` |
| `**/*.generated.*` | `ui/src/types/api.generated.ts` (180 KB) | the hand-written types in `ui/src/types/` |
| `**/META-INF/native-image/**/*` | `boot` reflection config (115 KB, regenerate with the agent, see `boot/CLAUDE.md`) | `@RegisterReflectionForBinding` |

`build/`, `dist/` and `node_modules` are not denied: they are gitignored (search skips them) and `build/test-results` is read on purpose.
The rules are in `.claude/settings.json`; Claude Code reads the shared settings of the **directory it starts in**, not of the repository
root, so `ui/`, `boot/` and `docs/` carry their own `.claude/settings.json` with the rules for their files. Add a
rule to the root file and to the module that holds the path. Worktree sessions get them with the checkout. To work on a denied file,
remove its rule in your own checkout for the session (a deny cannot be overridden by `allow` or `settings.local.json`).

## API-shape changes: refresh the snapshots in the same change

Changing anything that reaches the REST schema (a `*Request`/`*Response` DTO in `web`, a model it maps from such as `WorkflowDefinition`,
a controller signature or `@Schema`) makes `:boot:test` fail on `OpenApiSnapshotTest` ("ui/openapi.json is stale") and `ui` fail on
`apiTypesCheck`. Do this **before** `yk check`, not after it fails (the heal ladder cannot fix it: `openapi.json` is unreadable to it):

```bash
yk gradle :boot:test --tests "*OpenApiSnapshotTest" -Pyukta.openapi.update=true   # rewrites ui/openapi.json
yk tool -C ui node scripts/generate-api-types.mjs openapi.json  # rewrites ui/src/types/api.generated.ts
```
(Same as `pnpm run api:types -- openapi.json`, which needs a working corepack pnpm.) The test refreshes only `ui/openapi.json`; whether `docs/api/openapi.json` needs the same is unconfirmed (see `design/decisions/0001-input-requests.md`). Commit the regenerated files with the DTO change. Never hand-edit them.

## Commit Message Conventions

All commits follow **Conventional Commits** format. See `.gitmessage` template (shown when committing), `.git-commit-guide.md`, and `.claude/rules/git-workflow.md` for full details. The `.git/hooks/commit-msg` hook validates format automatically.

## Worktrees

`claude --worktree` and subagent worktree isolation create worktrees under `.claude/worktrees/`. `.claude/settings.json` sets
`worktree.symlinkDirectories` to `ui/node_modules`, so each one links to the main checkout's copy (about 440 MB) instead of installing its
own. `yk tool -C ui` (`tsc`, `vitest`) works through the link. Caveats: a `pnpm install` (Gradle's `ui` tasks run one) in such a worktree
writes into the shared copy, so run it deliberately when a branch changes `ui/package.json` or `ui/pnpm-lock.yaml`; worktrees made with
`git worktree add` (`yk` runs) do not get the link.

`worktree.sparsePaths` is **not** set (measured 2026-10-31): the tracked tree is 16 MB and a full worktree is created in 0.2 s (0.1 s sparse),
and a sparse tree breaks the build: Gradle fails with "Configuring project ':plugins:processors:internal' without an existing directory is not
allowed" because `settings.gradle.kts` includes every module, and `yk` itself lives in the sibling yukta-dogfood checkout. Keeping all modules and `examples`
leaves only `docs`, `design` and `.github` out, a few MB.

## Testing Strategy
- Unit tests use JUnit 5 + Mockito (for reactive: `reactor.test`).
- Use `StepVerifier` for testing reactive streams.
- Quality gates (Checkstyle, PMD, SpotBugs, OpenGrep) are enforced via `build-logic` — see `.claude/rules/coding-standards.md` for details.
- Minimum coverage thresholds are module-specific.

### Testing tiers
Unit tests keep a 100% floor per class (Java), per function (Go) and in Vitest. Above them, tests that drive a **real server**
(plan: `design/plans/integration-e2e-testing.md`). Start the server for the non-JVM tiers with `release/e2e-server.sh start`
(`url`, `key`, `viewer-key` print its details; `stop` ends it).

| Tier | Where | Server | Command | Runs on |
|---|---|---|---|---|
| Web and MCP integration | `e2e` module (`e2e/CLAUDE.md`) | in-JVM, random port | `yk gradle :e2e:test` | PRs that change platform modules |
| The same suite against a binary | `e2e` | jar, native, Docker | `yk gradle :e2e:test -Pyukta.e2e.url=<url> -Pyukta.e2e.key=<key>` | nightly, release |
| CLI e2e | `cli/e2e` (`//go:build e2e`) | `release/e2e-server.sh` | `YUKTA_E2E_URL=<url> YUKTA_E2E_KEY=<key> yk tool -C cli go test -tags e2e ./e2e/...` | nightly, `ci:e2e` label |
| UI, mocked | `ui/e2e/tests` | none (`page.route`) | `yk tool -C ui npx --no-install playwright test` | PRs that change `ui` |
| UI, real server | `ui/e2e/real` | `release/e2e-server.sh` | `yk tool -C ui env YUKTA_URL=<url> YUKTA_API_KEY=<key> YUKTA_VIEWER_KEY=<viewer-key> npx --no-install playwright test -c playwright.real.config.ts` | nightly, `ci:e2e` label |

The nightly tiers are the `e2e-nightly.yml` workflow. Load the `playwright` skill before writing a UI spec.

### Test Execution Policy (speed)
Waiting on tests is the main development bottleneck. **Run the smallest scope that covers your change.**

- Quick compile feedback: `yk gradle :<mod>:compileJava :<mod>:compileTestJava`.
- Default: `yk gradle :<mod>:test --tests '<FQCN>'` (repeat `--tests` per class; `Class.method` while iterating).
- A public contract or many classes in a package: `--tests 'com.infenia.yukta.<package>.*'`.
- Run `spotlessApply` once at the end, not after every edit. Don't use `clean` or `--rerun-tasks`.
- Run long builds with `run_in_background`; on failure read only the failing test's report in `build/test-results`.
- Locate tests via `<Class>Test` naming or codebase-memory, not repo-wide grep.

### Test Skill
Load the below skill for test generation and test improvement.
- **Name**: tests
- **Description**: Use for generating unit tests (Java, JUnit 5, Mockito, AssertJ).


## Imports
The yukta skill is imported so it is always loaded (also after /compact and in subagents), not only when its description matches.

@.claude/skills/yukta/SKILL.md
@.claude/rules/coding-standards.md
@.claude/rules/git-workflow.md

IMPORTANT: Navigate code with the `codebase-memory` MCP tools first (see `.claude/CLAUDE.md`); when the IDE is open, the intellij-index MCP tools are fine for refactoring.
For Java/TS references, definitions, renames, hierarchies and diagnostics use the `mcp__intellij-index__*` tools (skill: `ide-index-mcp`), not grep.
