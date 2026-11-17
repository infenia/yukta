# Integration and end-to-end test tiers

Plan for the tests above the unit tier: web integration, CLI end-to-end, MCP end-to-end and UI end-to-end. The goal is releases where every client surface (REST, `yukta-cli`, MCP, web UI) has been driven against a real server before shipping.

Status (2026-11-17): **accepted, nothing built.** The "today" statements were read from `origin/main` at 07931e3aa. Arun decided D1 to D4 on 2026-11-17. The work is filed as epic #2446; see [Issues](#issues). Each sub-issue cites a section of this file, and where they differ this file wins.

## Today

Unit coverage is strong. JaCoCo requires 100% LINE and BRANCH per class (`build-logic/.../com.infenia.yukta.jacoco-conventions.gradle.kts`), Go requires 100% per function (`cli/Makefile` `cover-check`), and Vitest requires 100% (`ui/vitest.config.ts`). Above that tier the picture is uneven, and no single place shows what runs where.

### Web (REST)
- `web` has 35 controllers. 21 of them have controller-slice tests (`WebTestClient.bindToController`, with no Spring context and no filters). The other 14 have only mock unit tests (`StepVerifier` on the controller method, no HTTP):
  - `AgentApiController` (13 mappings)
  - `AgentMcpController`
  - `AgentCredentialController`
  - `AiBackendController`
  - `ControlAuditController`
  - `EditionController`
  - `EventsController`
  - `MeController`
  - `MetricsController` (8)
  - `SupervisionController`
  - `SupervisorContractController`
  - `SupervisorsController` (18)
  - `SupervisorTypesController`
  - `WorkersController`
- The filters are unit-tested one by one: `ApiKey`, `HostOrigin`, `SecurityHeaders`, `AgentCredential`, `ControlVia`, `DeprecatedAlias`, `RequestLogging` and `RequestTracing`. So is `GlobalExceptionHandler`. Only six `boot` tests send real HTTP to a random port, so the chain is rarely exercised together with real controllers. The six are:
  - `RequestSizeLimitTest`
  - `BoundaryFuzzAcceptanceTest`
  - `SupervisorApiAcceptanceTest`
  - `PresetsAcceptanceTest`
  - `OpenApiSnapshotTest`
  - `plugins/McpPluginsAcceptanceTest`
- `boot/src/test` has 31 `@SpringBootTest` classes. Most use `webEnvironment = MOCK` and call services directly, not HTTP. There is no integration source set, no JUnit tag and no shared base class, so each class repeats its own annotations and properties. There is also no `src/test/resources/application*.yml`.
- JaCoCo reports are per module. A `boot` test that runs `web` code does not count toward `web` coverage.

### CLI (`cli/`, Go)
- There are 88 `_test.go` files, all against `httptest` fakes. The helpers are in `cli/internal/testutil/testutil.go` (`Server`, `Capture`).
- `cli/internal/client/contract_test.go` checks that every `METHOD /api/...` the client sends exists in `ui/openapi.json`. It checks method and path only; its own `ponytail:` note asks for a real-server job.
- `cli/test/integration_test.go` is misnamed: it tests URL normalisation and runs no server.
- Nothing runs `yukta-cli` against a real server.

### MCP (`mcp/`)
- `AppMcpTools` has 56 tools and `ConversationTools` 7. There are also 2 prompts and 3 resources. The endpoint is streamable HTTP at `/mcp` (stateless, async), with the same API keys as REST and roles enforced by `McpToolAccess`.
- Every provider has a unit test. `ToolTextDriftTest` keeps the roles in `docs/reference/mcp-tools.mdx` in step with the code, using reflection with no server.
- `mcp/src/test/java/com/infenia/yukta/McpEndpointIntegrationTest.java` sends 5 raw JSON-RPC calls to a random port. No test uses an MCP client SDK, and no test follows an agent journey across tools.

### UI (`ui/`)
- `ui/e2e/tests/` has 17 Playwright specs, all mocked with `page.route()` (not MSW). The guard in `ui/e2e/fixtures.ts` fails a test on any unmocked `/api/` call.
- One real-server spec, `ui/e2e/real/examples.spec.ts`, run by `playwright.real.config.ts`. Only the nightly `native-smoke.yml` runs it, through `release/ui-smoke.sh`.
- There are no page objects, and specs repeat local `envelope()` helpers.
- Routes without a spec: `/sessions/:id/edit`, `/supervisors/new`, `/supervisors/:id/edit`.
- `ui/e2e/__snapshots__/overview.spec.ts/` is orphaned, since the spec no longer exists.
- `.claude/skills/playwright/SKILL.md` is out of date: it describes MSW and `e2e/mocks/handlers.ts`, which don't exist.

### Real-server checks
- `release/smoke-test.sh`, `examples-smoke.sh` (which runs `examples/run-all.sh`), `ui-smoke.sh` and `console-smoke.sh` run against a running server or binary. They are bash plus curl and jq.
- Where they run:
  - `native-smoke.yml`: daily, against the native binary.
  - `examples-nightly.yml`: daily, against the bootJar.
  - `release.yml`: on tags and weekly.
- On PRs, only the mocked Playwright suite runs among the e2e-style tests (`ci.yml` `e2e-tests`, triggered when `ui` changed).
- An unfinished TypeScript smoke suite (`release/smoke/{api,mcp,workflow}.test.ts`) sits untracked in the `smoke-suite` worktree. This plan replaces it (D3).

## Decisions (Arun, 2026-11-17)

| # | Question | Decision |
|---|---|---|
| D1 | Where do the integration tests live? | **A new test-only Gradle module `e2e`**, with `architecture` as the precedent. It depends on `:boot` and starts the real application. |
| D2 | How are CLI e2e tests written? | **Go tests behind `//go:build e2e`** that drive `app.Main()` in-process against a real server URL. No new dependency. |
| D3 | The TypeScript smoke suite in the `smoke-suite` worktree? | **Folded into this plan.** Its API and MCP cases become Java tests in `e2e`. The shell scripts in `release/` remain as the binary check until E10 retires the overlap. |
| D4 | What gates a PR? | **The web and MCP integration tests (`:e2e:test`) gate PRs** that touch platform modules. CLI e2e and real-server Playwright run nightly, before release, and on PRs labelled `ci:e2e`. |

## Target

| Tier | Where | Server | Runs on |
|---|---|---|---|
| Unit | each module (unchanged) | none, mocks | every PR |
| Web and MCP integration | `e2e` module (Java, JUnit 5) | the app started in the test JVM on a random port | PRs that change platform modules |
| The same suite against a binary | `e2e` with `-Pyukta.e2e.url=<url>` | jar, native binary or Docker image | nightly native smoke, release |
| CLI e2e | `cli/e2e/*_test.go` (`//go:build e2e`) | bootJar started by `release/e2e-server.sh` | nightly, release, `ci:e2e` |
| UI real-server | `ui/e2e/real/` (Playwright) | bootJar started by `release/e2e-server.sh` | nightly, release, `ci:e2e` |
| UI mocked | `ui/e2e/tests/` (unchanged) | `page.route` mocks | PRs that change `ui` |

**One suite, two targets.** The `e2e` module runs the same Java scenarios either against an app it starts itself (on a PR) or against any URL (a native binary or Docker image at release). That one switch removes the need for a second, TypeScript or shell, API suite.

## Conventions (all tiers)

- **Isolation.** Each test creates uniquely named resources (`e2e-<test>-<short uuid>`) and deletes them. No test relies on order or on another test's data, which is required for running against one shared binary.
- **No sleeps.** Wait with explicit timeouts: Awaitility in Java (`awaitility` 4.3.0 is already in `gradle/libs.versions.toml`), `expect.poll`/`toPass` in Playwright, and a small `waitFor` loop in Go.
- **Determinism.** Workflows use only `echo`/`sh` processes and in-JVM plugins. A test that needs an outside service (an LLM, GitHub) skips with an assumption unless `examples/` already fakes the service.
- **Build data with the fixtures, not hand-written JSON.** In Java use `core` testFixtures (`core/src/testFixtures/.../testing/Fixtures.java`) or the example `session.json` files. In Playwright use the REST seed helper.
- **Run small.** `yk gradle :e2e:test --tests '<FQCN>'`. The in-JVM server starts once per test JVM and every class shares it.
- **Assert the contract, not the implementation.** Check the status code, the `{timestamp,status,message,path,data}` envelope, the field names, the exit codes and the visible text. Integration tests must not reach into beans.
- **Build and test only through `yk`** (see `CLAUDE.md`). `./gradlew bootRun` to run the app is allowed.

## E0: the `e2e` module and its support

**Module.**
- Add `include("e2e")` to `settings.gradle.kts`.
- `e2e/build.gradle.kts` applies `com.infenia.yukta.library-conventions`. It holds tests only and has no `src/main`, like `architecture/build.gradle.kts`. Its dependencies:
  - `testImplementation(project(":boot"))`
  - `testImplementation(testFixtures(project(":core")))`
  - `testImplementation(project(":test-support"))`
  - `testImplementation(libs.awaitility)`
  - the Spring Boot test starter
  - an MCP Java SDK client (SDK `mcpSdk` 2.1.0 is in the catalog). Use the client transport that ships with the MCP server starter, or add `io.modelcontextprotocol.sdk:mcp` under the catalog version.
- Pass `yukta.e2e.url` and `yukta.e2e.key` through from Gradle properties to system properties, the way `boot/build.gradle.kts` passes `fuzzTries`.
- Coverage: the module has no main classes. Make sure `verifyCoverageData` and `jacocoTestCoverageVerification` pass the way they do for `architecture`. If they don't, use `coverageConfig.noTests("test-only module: …")`.
- `ci-tasks.sh` derives modules from `settings.gradle.kts`, and `e2e` depends on `boot`, so any change to a JVM module marks `e2e` as affected. Give it its own shard: add `:e2e) echo e2e ;;` to `group_of` and add `e2e` to the shard loop and the `ci.yml` matrix, with `node: true` (the boot classpath bundles the UI jar). Upload the JUnit XML like the other shards.

**Support package `com.infenia.yukta.e2e.support`** (one class per concern, nothing more):

| Class | Job |
|---|---|
| `YuktaServer` | A JUnit 5 extension, registered with `@ExtendWith` or a meta-annotation `@E2eTest`. If `yukta.e2e.url` is set, it targets that URL and key. Otherwise it starts `YuktaApplication` (in `boot`) once per JVM through `SpringApplicationBuilder` on port 0, with test properties: plugins `all`, process allowed commands `sh,echo`, an ADMIN key and a VIEWER key and an OPERATOR key, in-memory stores. It stops on JVM shutdown. It exposes `baseUrl()` and `key(Role)`. |
| `Api` | A `WebTestClient` bound to `baseUrl()` (`WebTestClient.bindToServer()`) that sends `X-API-Key`. It has helpers `data(…)` to unwrap the envelope and `error(…)` to assert the error envelope. It never retries. |
| `Scenarios` | Domain steps: `applySession(json)`, `startWorkflow(session, workflow, payload)`, `awaitTerminal(execution, timeout)` (Awaitility), `deleteSession`. It names and tracks what it created and deletes it after each test through an extension callback. |
| `Mcp` | Opens an SDK `McpSyncClient` on `baseUrl()/mcp` with a given key. It has `tools()`, `call(name, args)` and `callExpectingError(...)`. |

`@E2eTest` is a meta-annotation for `@ExtendWith({YuktaServer.class, ErrorGuardExtension.class})` plus the `@Tag("e2e")` used in reports.

**Proof that it works.** One test, `HealthE2eTest`, checks that `/actuator/health` is UP and that `GET /api/sessions` with the ADMIN key is 200. It passes both with an in-JVM server and with `-Pyukta.e2e.url=http://127.0.0.1:9860` against `./gradlew bootRun` (`boot/CLAUDE.md` has the bootRun port and plugin settings).

## E1: move the random-port tests out of `boot`

- Move `RequestSizeLimitTest`, `BoundaryFuzzAcceptanceTest`, `SupervisorApiAcceptanceTest`, `PresetsAcceptanceTest` and `plugins/McpPluginsAcceptanceTest` from `boot/src/test/java/com/infenia/yukta/` to `e2e`. Rebase them on `@E2eTest`, `Api` and `Mcp`.
- Keep the `-PfuzzTries` and `-PfuzzSeed` passthrough on `e2e`.
- `OpenApiSnapshotTest` stays in `boot`: it writes `ui/openapi.json` and the docs refer to it there. The MOCK-environment acceptance tests also stay.
- **Coverage rule:** before deleting each test from `boot`, run `yk gradle :boot:test :boot:jacocoTestCoverageVerification` without it. If a `boot` class loses its only coverage, keep or add a focused test in `boot`. Don't add an exception.
- Done when `boot` and `e2e` are both green and the total test count is unchanged.

## E2: security and the error contract

Write these over real HTTP in the `e2e` package `web`:
- `AuthE2eTest` covers the following.
  - Keys and roles:
    - no key is rejected when keys are configured
    - a wrong key gets 401
    - VIEWER can read but gets 403 on a write
    - OPERATOR can control executions but not admin actions
    - ADMIN can do everything
  - The cases are a table: one representative endpoint per controller and per role, taken from the role rules the filters enforce.
  - Session-scoped keys are refused outside their sessions.
  - Both `Authorization: Bearer` and `X-API-Key` are accepted.
- `HostOriginE2eTest`: a foreign `Host` or `Origin` is refused, and loopback is allowed.
- `SecurityHeadersE2eTest`: the headers from `SecurityHeadersFilter` are on an API response, a static asset and an error.
- `ErrorContractE2eTest`. For each of the following, the response is the `GlobalExceptionHandler` envelope, with no stack trace and no class names:
  - a 400 from validation
  - a 404 for an unknown session or execution
  - a 405
  - a 413 (body limit)
  - a 415
  - a malformed JSON body
  - an unknown route
- `RateLimitE2eTest`: the MCP heavy-call limit answers with a tool error, not a 5xx. Set a low limit through a test property on a dedicated server only if `YuktaServer` supports per-class properties. Otherwise skip this when running against an external URL.

## E3: HTTP tests for the 14 controllers with mock-only tests

One `*E2eTest` per controller listed in [Today › Web](#web-rest). For each mapping, cover:
- the happy path, with status, envelope and the field names the UI types use (`ui/src/types/`)
- one validation failure, when the endpoint takes input
- one not-found, when it takes an id
- the role required

The agent API (`AgentApiController`, `AgentMcpController`, `AgentCredentialController`) is authenticated by an agent credential (`AgentCredentialFilter`). Create the credential through `/api/supervisors/{id}/credential` first, then use it.

Don't redo what the `web` slice tests already cover. Here the point is routing, JSON binding, filters and the real beans together.

## E4: workflow lifecycles and the examples

- `SessionLifecycleE2eTest`:
  - validate, then create, get, list, update and delete
  - import and export, if exposed
  - version list, a note, a diff and a rollback
- `ExecutionLifecycleE2eTest`:
  - start → SSE progress stream to a terminal status (read the stream with `WebTestClient`, using `returnResult(ServerSentEvent.class)`)
  - results, logs (`LogManagementController`) and node output
  - the execution search
- `ExecutionControlE2eTest`:
  - pause, resume and stop on a running workflow (a `sh` node with `sleep` that the test controls)
  - node breakpoint, retry and skip, as `ExecutionControlController` exposes them
- `InputRequestE2eTest`: a workflow that asks for input, answered through `InputRequestsController`, then through `TokenAnswerController`.
- `NotificationsE2eTest`: an inbox notification appears and is marked read.
- `WebhookE2eTest`: `POST /hooks/...` starts the workflow it is bound to.
- `ExamplesE2eTest`: a `@ParameterizedTest` over `examples/*/session.json`.
  - It applies each session and runs the workflows that `examples/<n>/run.sh` runs, with its sample payload, then checks the same outcome the script checks.
  - When a plugin is missing (`require_plugin` in `examples/lib/common.sh`), it becomes `Assumptions.abort`.
  - When this test covers an example, the example's `run.sh` stays for users and for the nightly `examples-nightly.yml`, but CI correctness now rests on this test.
  - Fold in the cases of `release/smoke/workflow.test.ts` and `api.test.ts` from the `smoke-suite` worktree (copy what is useful; don't merge the TypeScript).

## E5: CLI end-to-end

- **`release/e2e-server.sh start|stop|url`.**
  - It builds the bootJar if `boot/build/libs/boot-*.jar` is missing (skipping `-plain.jar`; CI builds it first with `yk`/Gradle).
  - It starts the jar on a free port with an ADMIN key and the plugin and process settings of E0, waits for `/actuator/health`, and writes the pid and URL to `build/e2e-server/`.
  - `stop` kills the process and prints the tail of its log on failure.
  - It takes an optional `JAVA_TOOL_OPTIONS` for the JaCoCo agent (E11).
  - `examples-nightly.yml` switches to it.
- **Tests.**
  - Put them in `cli/e2e/` with `//go:build e2e`.
  - `TestMain` reads `YUKTA_E2E_URL` and `YUKTA_E2E_KEY` and fails when they are unset; the tag already keeps the tests out of the unit run.
  - Drive `app.Main()` in-process with `os.Args` and `testutil.Capture`, as `cli/internal/app/app_test.go` does.
- **Scenarios:**
  - `session apply/list/get/delete`, using an example `session.json`
  - `workflow start` with `--follow`: the SSE stream ends at the terminal status
  - `executions`, `logs`, `result`
  - `inputs` list and answer
  - `notifications`
  - `supervisors` list and get
  - `whoami`
  - `-o json` output decodes into the same fields as the REST response, which closes the field-drift gap in `contract_test.go`
  - the exit codes: 0, 3 for an unknown id, 5 for a `--timeout` of 1ms on a slow call
  - the token flags `--token`, `--token-file` and the env var
- **Wiring.**
  - `cli/Makefile` gets a target `e2e` (`go test -tags e2e ./e2e/...`).
  - `cli/build.gradle.kts` gets a task `goE2e` that is not part of `check`.
  - The `cover-check` exclusions must not count `cli/e2e`.
- **CI.** Add a job `cli-e2e` to a new workflow `e2e-nightly.yml`, run nightly, on dispatch and on PRs with the label `ci:e2e`. It builds the bootJar, runs `e2e-server.sh start`, runs `make e2e`, then `stop`.
- Delete `cli/test/integration_test.go`, and move its URL-normalisation cases to `cli/internal/client`.

## E6: MCP end-to-end

Use `Mcp` from E0 (the SDK client), not raw JSON-RPC. Put the tests in the `e2e` package `mcp`.
- `McpSurfaceE2eTest`:
  - `tools/list` returns the 56 + 7 tools (with agents enabled), each with an input schema and an output schema where the tool declares one
  - the names and roles match `docs/reference/mcp-tools.mdx` (reuse the table parser from `ToolTextDriftTest`; move it to a small shared helper if both need it)
  - prompts and resources list and read
- `McpAgentJourneyE2eTest`:
  - `create_session` → `start_workflow` → `get_workflow_status` and `get_execution_tree` → `get_node_output` → `get_execution_logs` → `get_execution_result`
  - a run paused and resumed with `control_workflow`
  - `set_node_breakpoint` and `control_node`
  - `list_workflow_versions` → `diff_workflow_versions` → `rollback_workflow`
- `McpInputsE2eTest`: `list_pending_inputs` → `answer_input`, and `escalate_input`.
- `McpSupervisorE2eTest`: `save_supervisor`, then `get_supervisor`, then `replay_supervisor` → `rollback_supervisor`, and `set_assignments`/`check_assignment`.
- `McpNotificationsE2eTest`: `list_notifications`, then `mark_notifications_read`.
- `McpAccessE2eTest`:
  - for every tool, the lowest role in `McpToolAccess` succeeds and the role below it is refused, with VIEWER, OPERATOR and ADMIN keys
  - a session-scoped key sees only its sessions
- `McpErrorsE2eTest`: an unknown id gives `isError: true` with a message, not a JSON-RPC error. An oversized body gets 413 and a malformed one gets 400 (already in `BoundaryFuzzAcceptanceTest`; don't duplicate it).
- Move the 5 tests of `mcp/src/test/java/com/infenia/yukta/McpEndpointIntegrationTest.java` into these and delete it. Check that `mcp` coverage holds without it (as in E1). Fold in the cases of `release/smoke/mcp.test.ts`.
- The agent conversation tools (`ConversationTools`) need an agent node. Cover them only with the `terminal`/`sh` preset that runs without an outside AI CLI, or skip with an assumption.

## E7: Playwright reuse layer and the mocked gaps

- `ui/e2e/pages/`: one page object per screen: `SessionsPage`, `SessionDetailPage`, `WorkflowDetailsPage`, `WorkflowEditorPage`, `SupervisorsPage`, `SupervisorEditorPage`, `InboxPage`, `RunsPage`, `ControlPage`.
  - Each takes a `Page`, has a `goto(...)`, and exposes locators and actions by role, label or test id.
  - They hold no assertions beyond waits.
  - They are shared by the mocked and the real tier.
- `ui/e2e/api-envelope.ts`: one `envelope()` helper; replace the local copies in `control.spec.ts`, `control-visual.spec.ts` and the others.
- New mocked specs for the routes without one:
  - `session-edit.spec.ts` (`/sessions/:id/edit`)
  - `supervisor-editor.spec.ts` (`/supervisors/new`, `/supervisors/:id/edit`)
  - `/showcase/dashboard`, only if it ships to users; otherwise leave it out and say so in the PR
- Delete `ui/e2e/__snapshots__/overview.spec.ts/`.
- Move specs to the page objects as they are touched. Don't rewrite all 17 in this issue.

## E8: real-server Playwright journeys

- `ui/e2e/real/api.ts`: seed and clean up through REST: apply a session, start a workflow, create a supervisor, delete by name prefix. Setup goes through it, not through clicks.
- Specs in `ui/e2e/real/`, using the page objects from E7:
  - `sessions.spec.ts`: create from a template, edit, delete.
  - `run.spec.ts`: start a workflow, watch the node states change, then the logs appear live over SSE, then the result.
  - `inbox.spec.ts`: a workflow asks for input; answer it from the inbox and the run finishes.
  - `supervisors.spec.ts`: create and edit a supervisor, and assign it.
  - `control.spec.ts`: pause and resume a running execution from the control page.
  - `runs.spec.ts`: the run appears in runs and history with its status.
  - `roles.spec.ts`: with a VIEWER key, the write actions are hidden or refused.
- `playwright.real.config.ts` takes the key from `YUKTA_API_KEY`, as `release/ui-smoke.sh` does. Keep traces on failure.
- **CI.** Add a `ui-real` job to `e2e-nightly.yml` (E5) using `release/e2e-server.sh`. It uploads the HTML report and traces on failure. `native-smoke.yml` keeps running `ui-smoke.sh` against the native binary.

## E9: documentation

- Rewrite `.claude/skills/playwright/SKILL.md` to cover:
  - `page.route` mocking and the unmocked-API guard in `ui/e2e/fixtures.ts`
  - `mockSessionsApi` and `mockWorkflowApi`
  - page objects
  - the real tier and how to run it locally (`release/e2e-server.sh start`, then `yk tool -C ui npx --no-install playwright test -c playwright.real.config.ts`)
  - the actual spec list
- Remove the MSW and `e2e/mocks/handlers.ts` text and the dead link to `playwright-testing-guide.md`.
- Add `e2e/CLAUDE.md` covering:
  - what the module is
  - `@E2eTest`, `Api`, `Scenarios`, `Mcp`
  - how to run it in-JVM and against a URL
  - the conventions above
- Add a "Testing tiers" section to the root `CLAUDE.md` with the [Target](#target) table and one command per tier. Add a line to `cli/CLAUDE.md` and `ui/CLAUDE.md`.
- Update `docs/` only if a contributor guide there describes the tests; search first.

## E10: the suite against released artifacts

- `native-smoke.yml`: after the binary is up, run `yk`-equivalent `./gradlew :e2e:test -Pyukta.e2e.url=http://127.0.0.1:9858 -Pyukta.e2e.key=<key>`. CI runs Gradle directly, since `yk` is a local tool.
- `release.yml`: the same against the Linux binary and the Docker image (`build-image.sh` already starts it on 18080).
- Tests that need a server property the binary doesn't have (for example a low rate limit) are marked `@InJvmOnly` (an assumption in `YuktaServer`) and skip in external mode.
- Retire the overlap: with these green, drop from `release/smoke-test.sh` the checks the Java suite now covers (keep health, version and the quickstart as a fast first check). `examples-smoke.sh` stays only for examples that `ExamplesE2eTest` cannot run. Delete the `smoke-suite` worktree's untracked TypeScript files.

## E11: integration coverage report

- In `e2e-nightly.yml` and `release.yml`, start the server jar through `release/e2e-server.sh` with the JaCoCo agent (`-javaagent:jacocoagent.jar=destfile=…`). Run `:e2e:test` and `make e2e` against it.
- Merge the exec file into one report over the classes of `web`, `core`, `mcp` and the plugins, with a small Gradle `JacocoReport` task in `e2e`.
- Upload it to Codecov with the flag `e2e`.
- This report shows integration coverage. It does not gate, and it doesn't change the per-module unit floors.
- Write in the PR which packages the e2e tiers reach that the unit tests don't, and the reverse, as input for the next round.

## Order

`E0` → (`E1`, `E2`, `E3`, `E4`, `E6` in any order) → `E10` → `E11`.
CLI: `E0` → `E5`.
UI: `E7` (needs nothing) → `E8` (needs `E5`'s `e2e-server.sh`).
`E9` after `E0` and `E7`, updated again at the end.

One PR per issue. Each PR runs `yk check`, and the last PR of a phase also runs `yk check --full`.

## Verification per tier

| Tier | Command | Expect |
|---|---|---|
| e2e in-JVM | `yk gradle :e2e:test` | green; `:boot:test` still green with coverage |
| e2e against a URL | `./gradlew bootRun` on :9860 (see `boot/CLAUDE.md`), then `yk gradle :e2e:test -Pyukta.e2e.url=http://127.0.0.1:9860 -Pyukta.e2e.key=<key>` | green, `@InJvmOnly` tests skipped |
| CLI e2e | `release/e2e-server.sh start`, then `YUKTA_E2E_URL=… YUKTA_E2E_KEY=… yk tool -C cli go test -tags e2e ./e2e/...` | green; `yk tool -C cli go test ./...` unchanged |
| UI mocked | `yk tool -C ui npx --no-install playwright test` | green |
| UI real | `release/e2e-server.sh start`, then `YUKTA_URL=… yk tool -C ui npx --no-install playwright test -c playwright.real.config.ts` | green |

## Issues

Epic: #2446

| § | Issue | Scope |
|---|---|---|
| E0 | #2447 | test(e2e): e2e module, server/API/MCP support and the CI shard |
| E1 | #2448 | test(e2e): move the random-port acceptance tests from boot into e2e |
| E2 | #2449 | test(e2e): security and error-contract integration tests |
| E3 | #2450 | test(e2e): HTTP tests for the 14 controllers with mock-only tests |
| E4 | #2451 | test(e2e): workflow lifecycle tests and the examples as tests |
| E5 | #2452 | test(cli): e2e server script and CLI end-to-end tests |
| E6 | #2453 | test(e2e): MCP end-to-end with the SDK client |
| E7 | #2454 | test(ui): Playwright page objects and the mocked-route gaps |
| E8 | #2455 | test(ui): real-server Playwright journeys and the ui-real job |
| E9 | #2456 | docs: testing tiers, the e2e module guide and the Playwright skill |
| E10 | #2457 | ci: run the e2e suite against the native binary and release artifacts |
| E11 | #2458 | ci: integration coverage report from the e2e tiers |
