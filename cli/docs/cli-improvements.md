# cli — Review, Improvements & Optimisations

> Scope: `cli/` (Go 1.26, Cobra) plus its `build.gradle.kts`, reviewed on 2026-10-09 against
> `main` @ `8d3161b5`. **All 13 findings are fixed** (2026-10-10): main code went from ≈3.7k to ≈2.9k
> lines, tests from ≈11.5k to ≈8.0k, and `build.gradle.kts` from 578 to 48 lines.
> Companion: [`cli-module-guide.html`](cli-module-guide.html) is the interactive guide, including a command map.

## Status on 2026-10-15

The review below is the record of 2026-10-09. The interactive guide's *Risks and improvement ideas*
board has the current list with impact and effort. Changes since, checked against `main`:

- **New commands:** `controlbus watch` (the event stream, `--quiet` for problems only), `result`,
  `workflow node retry | breakpoint | signal`, and `session apply` is now registered (it was written
  and tested but never added to the session group).
- **Still open:** no paging flags (`limit` / `cursor`), no stop mode, no idempotency key, no retries,
  no `Last-Event-ID` or reconnect (also for `watch`), no `yukta version`, a stale README
  (`yukta-darwin`, `goBuildDev`, `~/.yukta/config`, `/api/v1`), 12 calls on deprecated
  `/api/workflow/...` paths.
- **Still no command for:** validate, session config, supervisors, workflow versions, approvals,
  scheduler limits and priority, recover, run search and control audit, workers.
- **New:** zone-less server timestamps are parsed in the CLI's time zone (`localTimestamps`), and
  `--quiet` classifies lines by keywords copied from the server (`keywordProblems`).

## 1. Verdict

The CLI was a clean, thin REST client with robustness and build-hygiene problems. Those are now fixed:

- Cobra commands are grouped by resource (`session`, `workflow`, `workflow node`,
  `workflow executions`, `logs`, `plugin`, `controlbus`).
- One `client` package wraps the HTTP calls, with an SSE scanner for live status and logs.
- IDs are path-escaped, non-streaming requests time out (`--timeout`, default 30 s), and the
  client can send a bearer token (`--token`) and a start payload (`--payload`).
- The build is reproducible: lint, security and vulnerability tools are pinned, Go errors are
  visible, coverage is always regenerated, and Gradle just calls `make`.
- The test-only interfaces and the 612-line mock are gone. Commands are tested against
  `httptest.Server`.

### Scorecard

| Area | Before | After | Notes |
|---|---|---|---|
| Command design | 4/5 | **4.5/5** | Mirrors the REST resources. Repeated commands now share `commands.Action` |
| Correctness | 3/5 | **4.5/5** | Escaped paths, request timeout |
| Server parity | 3/5 | **4/5** | Payload, auth, summaries, graph, executions, log entries. No OpenAPI-generated client |
| Build | 2/5 | **4.5/5** | Pinned tools, visible errors, fresh coverage, one source of truth (`Makefile`) |
| Tests | 4/5 | **4.5/5** | Real HTTP instead of mocks. Per-function 100% coverage still enforced |

---

## 2. Findings

Every finding below is **✅ Fixed** (see the *Fixed* line on each). Severity: **P0** fix now · **P1** fix next · **P2** should fix · **P3** clean-up. Effort: S < ½ day, M ≈ 1–2 days.

### 2.1 Correctness and parity

#### C-1 · P1 · Path segments aren't escaped — Effort S
- **Where:** `internal/client/workflow.go`, `session.go`, `controlbus.go`, `plugin.go`, `logs.go`
  build paths with `fmt.Sprintf("/api/workflow/%s/%s/node/%s/pause", sid, eid, nid)`. Query
  strings already use `url.Values`.
- **Impact:** `--node "a/../../sessions"` or an ID containing `?` or `#` changes the endpoint that
  gets called. Even for innocent IDs with spaces, you get confusing 404s.
- **Fix:** A helper, `func p(parts ...string) string { … url.PathEscape(part) … }`, used for every
  path.
- **✅ Fixed:** `apiPath(format, args…)` in `internal/client/client.go` escapes every ID with `url.PathEscape`; all client paths use it. Query strings still use `url.Values`. Test: `TestAPIPathEscapesSegments`.

#### C-2 · P1 · There's no timeout on non-streaming requests — Effort S
- **Where:** `NewClient` uses `http.Client{Timeout: 0}` for all calls.
- **Fix:** Add `--timeout` (default 30 s), applied with `context.WithTimeout` for normal requests.
  Streams keep using the command's cancel context (Ctrl-C).
- **✅ Fixed:** `--timeout` (default 30 s, `0` disables) sets `Client.Timeout`, applied with `context.WithTimeout` in `doRequest`. Streams don't go through `doRequest`, so they keep running until EOF or Ctrl-C.

#### C-3 · P2 · No auth support — Effort S
- When `web` adds an API key (W1), every CLI call fails.
- **Fix:** `--token` / `YUKTA_TOKEN` → `Authorization: Bearer …`, set in `newRequestWithBody`.
- **✅ Fixed:** `--token` / `YUKTA_TOKEN` sets `Authorization: Bearer …` in `newRequestWithBody`, so streams send it too.

#### C-4 · P2 · `workflow start` can't send a payload — Effort S
- The server now accepts `payload` on `POST /api/workflow/start` (uncommitted change). The CLI
  sends only `sessionId` and `workflowId`.
- **Fix:** `--payload '{"k":"v"}'` or `--payload-file` (the commands take positional IDs, so a flag fits best).
- **✅ Fixed:** `workflow start --payload '{"k":"v"}'` or `--payload-file <file>` (mutually exclusive). The payload must be a JSON object, matching the server's `Map<String, Object>`; it is omitted when not given.

#### C-5 · P3 · No commands for newer endpoints — Effort S
- These have no CLI command: `/api/sessions/summaries`,
  `/api/sessions/{sid}/workflows/{wid}/graph`, `/api/sessions/{sid}/workflows/{wid}/executions`,
  and the structured log stream `/logs/entries`.
- **Fix:** Add them as needed. Generating the client from the server's OpenAPI document
  (`/v3/api-docs`) would prevent future drift.
- **✅ Fixed:** `session summaries`, `session workflow graph <sid> <wid>`, `session workflow executions <sid> <wid>` and `logs --entries` (structured entries as JSON). Generating the client from OpenAPI was not done; it is only worth it if the endpoint list keeps growing.

### 2.2 Build

| ID | Finding | Fix | Status |
|---|---|---|---|
| C-6 | `go run …golangci-lint@latest`, `gosec@latest` and `govulncheck@latest` during `check`: non-reproducible, and executes unpinned upstream code. The v1 module path for golangci-lint may also not support Go 1.26, and `.golangci.yaml` is in v1 format | Pin the versions with a `tools.go` / Go 1.24+ `tool` directive in `go.mod` (`go tool golangci-lint`). Migrate the config to the v2 format if you upgrade | ✅ Fixed: pinned in the `Makefile` as `go run <pkg>@<version>` (golangci-lint v2.14.0, gosec v2.29.0, govulncheck v1.8.0); `.golangci.yaml` migrated to v2 |
| C-7 | `goBuild`, `goBuildAll`, `goVet` and `goModTidy` start processes without reading stdout or stderr. Compiler errors aren't shown, and large output could block the process | Use Gradle `Exec` tasks (output is inherited), or `redirectErrorStream(true)` plus `inheritIO()` | ✅ Fixed: Gradle tasks are `Exec` calls to `make`, which inherits stdout and stderr |
| C-8 | `goCoverageCheck` reuses an existing `coverage.out`, which may be stale, so outdated coverage can pass. Its YAML is parsed with regexes | Always regenerate, or make it depend on `goTestCoverage` with inputs and outputs. Read thresholds with a small Go program, or keep them in Gradle | ✅ Fixed: `cover-check` depends on `cover`, which always rewrites `coverage.out`; thresholds live in the `Makefile`; `.codecov.yaml` is gone |
| C-9 | About 400 lines of Kotlin duplicate the `Makefile` targets | Let Gradle call `make <target>` through `Exec` and keep the logic in one place | ✅ Fixed: `build.gradle.kts` is 48 lines of `makeTask(...)` wrappers |
| C-10 | `goBuild` maps every non-ARM architecture to `amd64` | Use `go env GOARCH`, or leave GOOS and GOARCH unset for native builds | ✅ Fixed: `make build` uses `go env GOOS` / `GOARCH` (override with `GOOS=` / `GOARCH=`) |

**Notes from the build fixes**

- Pinning golangci-lint v2 and gosec showed problems the old `@latest` runs had hidden: 32 unchecked
  errors (tests and `Close`/`Fprintln` calls, now configured in `.golangci.yaml`) and 5 gosec findings
  (3 user-chosen file reads annotated `#nosec G304`, 2 unchecked errors fixed).
- The fresh coverage check immediately caught untested branches in the new code, which a stale
  `coverage.out` would have let through.
- `make vuln` follows the Go toolchain in use. `go.mod` pins `go1.26.9`, which is clean; a local
  `go1.27.1` reports standard-library vulnerabilities fixed in 1.27.2.
- Tools are pinned with versions in the `Makefile` rather than `tool` directives in `go.mod`, which
  would add hundreds of indirect requirements to the CLI's module.

### 2.3 Simplification

| ID | Finding | Fix | Status |
|---|---|---|---|
| C-11 | `RequestFactory`, `DefaultRequestFactory` and the `HTTPDoer` interface exist only for tests, plus a 612-line `client/mock.go` | Test against `httptest.NewServer`. Delete the interfaces and the mock (about 650 lines) | ✅ Fixed: `RequestFactory`, `HTTPDoer`, `ClientInterface` and `mock.go` are deleted; tests use `internal/testutil` (an `httptest` server) |
| C-12 | `NewClient("")` in `app.Run`, then `PersistentPreRun` overwrites `BaseURL` | Create the client after the flags are parsed | ✅ Fixed: `--url`, `--token` and `--timeout` are bound straight to the client's fields, so there is no `PersistentPreRun` overwrite |
| C-13 | Each Cobra command lives in its own file of about 40 lines, with nearly identical bodies (pause, resume, step enable and disable…) | A table-driven helper `simpleAction(use, short, fn)` would remove about 400 lines | ✅ Fixed: `commands.Action` builds each "call and print" command; 12 command files are gone |

---

## 3. Roadmap

| Phase | Items | Outcome | Status |
|---|---|---|---|
| **1. Safe requests** (½ day) | C-1, C-2 | Correct endpoints and no hangs | ✅ Done |
| **2. Parity** (1 day) | C-3, C-4, C-5 | Works with an authenticated server and payloads | ✅ Done |
| **3. Reproducible build** (1 day) | C-6, C-7, C-8, C-10 | Pinned tools and visible errors | ✅ Done |
| **4. Trim** (1 day) | C-9, C-11, C-12, C-13 | Go code and tests: 15.2k → 11.0k lines (−4.2k, more than the 1.5k planned); Gradle script: 578 → 48 | ✅ Done |
