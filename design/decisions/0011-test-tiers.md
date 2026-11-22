# ADR 0011: Integration and end-to-end test tiers

- **Status:** accepted on 2026-11-17. The `e2e` module, `release/e2e-server.sh`, `cli/e2e`, `ui/e2e/real` and the `e2e-nightly.yml` workflow exist; the commands are in the root `CLAUDE.md` ("Testing tiers"). Check the epic for the remaining sub-issues.
- **Date:** 2026-11-17 (condensed from the integration-e2e-testing plan on 2026-11-22)
- **Decided by:** Arun, 2026-11-17 (D1 to D4).
- **Plan:** [`design/plans/integration-e2e-testing.md`](../plans/integration-e2e-testing.md) (kept while the epic is open: its sub-issues cite its sections E0 to E11).
- **Epic:** #2446 (sub-issues #2447 to #2458, one per area E0 to E11). Module guide: `e2e/CLAUDE.md`.

## Context

Unit coverage is strong (100% per class in Java, per function in Go, and in Vitest). Above it the picture was uneven: 14 of 35 web controllers had only mock unit tests, only six `boot` tests sent real HTTP, no Go test ran the CLI against a real server, MCP was tested with five raw JSON-RPC calls and no agent journey, the UI had only mocked Playwright specs and one real-server spec run nightly, and the shell smoke scripts were bash plus curl. The goal: every client surface (REST, `yukta-cli`, MCP, web UI) is driven against a real server before a release.

## Decisions

| # | Decision | Outcome |
|---|---|---|
| D1 | Where integration tests live | A **test-only Gradle module `e2e`** (precedent: `architecture`) that depends on `:boot` and starts the real application. |
| D2 | CLI e2e | **Go tests behind `//go:build e2e`**, driving `app.Main()` in-process against a real server URL. No new dependency. |
| D3 | The TypeScript smoke suite | Folded in: its API and MCP cases become Java tests in `e2e`. The `release/` shell scripts stay as the binary check until the overlap is retired. |
| D4 | What gates a PR | **Web and MCP integration (`:e2e:test`) gates PRs** that touch platform modules. CLI e2e and real-server Playwright run nightly, before release, and on PRs labelled `ci:e2e`. |
| D5 | One suite, two targets | The same Java scenarios run against an app the test starts (PR) or any URL (`-Pyukta.e2e.url`, for a jar, native binary or Docker image at release). A `@InJvmOnly` assumption skips tests that need a server property the binary lacks. |
| D6 | Conventions | Unique resource names and cleanup (no order dependence); no sleeps (Awaitility, `expect.poll`, a `waitFor` loop); deterministic workflows (`echo`/`sh`, in-JVM plugins); build data from fixtures; assert the contract (status, envelope, field names, exit codes), never beans; build and test only through `yk`. |
| D7 | MCP | Use the SDK client, not raw JSON-RPC; test the surface against `docs/reference/mcp-tools.mdx`, agent journeys, roles per tool (lowest role passes, the one below is refused) and error shape (`isError: true`, not a JSON-RPC error). |
| D8 | UI | Page objects shared by the mocked and real tiers, one `envelope()` helper, mocked specs for routes without one, real journeys seeded through REST, not clicks. |
| D9 | Coverage | Move tests out of `boot` and `mcp` only if the class keeps its coverage (no new exceptions). A JaCoCo agent on the server jar yields an integration coverage report (Codecov flag `e2e`); it informs, it does not gate. |

## Consequences

- The `e2e` module needs its own CI shard (it depends on `boot`, so any JVM change marks it affected) and the UI jar on its classpath.
- The tiers table with one command per tier is in the root `CLAUDE.md`.
- The `playwright` skill was out of date (MSW) and is rewritten for `page.route` mocks, page objects and the real tier.
- CI runs Gradle directly for the artifact checks; `yk` is a local tool.
