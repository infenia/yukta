---
name: playwright
description: Write or change Playwright tests for the Yukta UI (ui/e2e). Covers the mocked specs (page.route, no backend) and the real-server specs (ui/e2e/real, against release/e2e-server.sh), the page objects, and how to run both.
allowed-tools: Read, Write, Bash, Glob, Edit
context: fork
---

# Playwright tests for the Yukta UI

Two tiers, both in `ui/`. Run every command through `yk` (`yk tool -C ui npx --no-install playwright test ...`), never bare `npx`/`pnpm`.

| Tier | Config | Server | Specs |
|---|---|---|---|
| Mocked | `playwright.config.ts` | none: every `/api` call is mocked with `page.route` | `e2e/tests/*.spec.ts` |
| Real | `playwright.real.config.ts` | a real server (`release/e2e-server.sh`) | `e2e/real/*.spec.ts` |

## Mocked tier

```ts
import { test, expect } from '../fixtures';
import { envelope } from '../api-envelope';
```

- **`e2e/fixtures.ts`** extends `test`. Its `page` fixture fails the test on any `/api` call that no route mocked (it answers 501 and lists the calls), and answers a few endpoints every page asks for (identity, notifications). A route you register later wins.
- **`mockSessionsApi(page, { status?, sessions? })`** mocks the session list (`/api/sessions/summaries`). `mockSessions` is its default data.
- **`mockWorkflowApi(page, { graph?, executions?, progress?, logs? })`** mocks the workflow details page: graph, runs, progress and the SSE logs (`sseBody(frames)` builds an event stream body).
- **`envelope(path, data, status?)`** (`e2e/api-envelope.ts`) is the one `{timestamp,status,message,path,data}` response helper. Use it in every `route.fulfill`; do not write a local copy.
- Register routes **before** `page.goto`.

### Page objects (`e2e/pages/`)

`SessionsPage`, `SessionDetailPage`, `WorkflowDetailsPage`, `WorkflowEditorPage`, `SupervisorsPage`, `SupervisorEditorPage`, `InboxPage`, `RunsPage`, `ControlPage`. Each takes a `Page`, has `goto(...)`, and exposes locators and actions by role, label or test id. They hold waits, not assertions. Both tiers use them; move a spec to its page object when you touch it.

### Mocked specs (`e2e/tests/`)

`control`, `control-visual`, `delete`, `error-handling`, `inbox`, `plugins`, `roles`, `runs`, `screenshots`, `scrollbars`, `session-edit`, `session-import`, `session-list`, `session-new`, `supervisor-editor`, `supervisors`, `theme`, `workflow-details`, `workflow-editor`.

```bash
yk tool -C ui npx --no-install playwright test                      # all
yk tool -C ui npx --no-install playwright test e2e/tests/inbox.spec.ts
yk tool -C ui npx --no-install playwright test --grep "pattern"
```

## Real tier

Drives the UI of a running server, no mocks. `e2e/real/api.ts` (`RealApi`) seeds and cleans up through REST (apply a session, start a workflow, create a supervisor, assign, delete); setup never goes through clicks. `e2e/real/fixtures.ts` gives every test an `api` fixture that deletes what it made, and puts the API key in the tab's sessionStorage.

Specs: `sessions` (create from a template, edit, delete), `run` (logs live over SSE, then SUCCESS), `inbox` (answer an input), `supervisors` (create, edit, assign), `control` (pause and resume), `runs` (history shows the run), `roles` (a VIEWER key is refused writes), `examples` (every bundled template, examples run from the UI).

Run it locally:

```bash
yk gradle :boot:bootJar                       # once, and after a backend change
release/e2e-server.sh start                   # prints the URL; ADMIN key: `key`, VIEWER key: `viewer-key`
yk tool -C ui env YUKTA_URL=<url> YUKTA_API_KEY=<key> YUKTA_VIEWER_KEY=<viewer-key> \
  npx --no-install playwright test -c playwright.real.config.ts
release/e2e-server.sh stop
```

CI runs it as the `ui-real` job of `e2e-nightly.yml` (nightly, on dispatch, and on a PR labelled `ci:e2e`). `release/ui-smoke.sh <url>` runs it against a native binary.

## Rules

- No `page.waitForTimeout()`. Wait on something visible, `expect(...).toBeVisible()`, or poll with `expect.poll` / `toPass`.
- Selectors, best first: role, label, `data-testid`, text. Scope with `page.getByRole('main')` when a toast repeats text.
- Real tier: name everything `e2e-<test>-<random>` (`unique('x')`), clean up through the `api` fixture, use only `sh`/`echo` workflows, no order between specs. A rerun on the same server must be green.
- Every spec is independent and fast; assert what the user sees or the API contract, not implementation.
- Changed UI text or a route: update the page object, then the specs.

## Debugging

```bash
yk tool -C ui npx --no-install playwright test --headed --debug
yk tool -C ui npx --no-install playwright show-report
```
A failing real run keeps its trace in `ui/test-results/` (`retain-on-failure`).
