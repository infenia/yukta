# cli — Review, Improvements & Optimisations

> Scope: `cli/` (Go 1.26, Cobra; ≈3.7k main lines, ≈11.5k test lines) plus its `build.gradle.kts`
> (≈400 lines), reviewed on 2026-10-09 against `main` @ `8d3161b5`.
> Companion: [`cli-module-guide.html`](cli-module-guide.html) is the interactive guide, including a command map.

## 1. Verdict

The CLI is a clean, thin REST client:

- Cobra commands are grouped by resource (`session`, `workflow`, `workflow node`,
  `workflow executions`, `logs`, `plugin`, `controlbus`).
- One `client` package wraps the HTTP calls.
- There's an SSE scanner for live status and logs, and table/JSON output.
- The server URL comes from `--url` or `YUKTA_SERVER_URL`.
- Tests are extensive (a 3:1 test-to-code ratio).

The issues are robustness and build hygiene:

- **Path segments aren't escaped.** IDs are put into URLs with `fmt.Sprintf("/api/workflow/%s/%s/…")`.
  A node or session ID containing `/`, `?` or `#` targets a different endpoint.
- **There's no request timeout** (`http.Client{Timeout: 0}`). A hung server hangs every command,
  not just the streams.
- **The CLI can't follow the server's evolution.** There's no auth header (needed when web W1
  lands) and no `--payload` on `workflow start` (the server now accepts one).
- **The Gradle wrapper is fragile:**
  - It runs `golangci-lint`, `gosec` and `govulncheck` with **`@latest`** during `check`. That's
    non-reproducible and runs unpinned code.
  - `goBuild` doesn't show `go build` errors.
  - `goCoverageCheck` reuses a stale `coverage.out`.
  - About 400 lines of Kotlin duplicate the `Makefile`.
- **Test-only abstractions:** `RequestFactory`, `HTTPDoer` and a 612-line `mock.go`, where
  `httptest.Server` would do.

### Scorecard

| Area | Score | Notes |
|---|---|---|
| Command design | 4/5 | Mirrors the REST resources. Consistent flags |
| Correctness | 3/5 | Unescaped paths, no timeout |
| Server parity | 3/5 | No payload, no auth, no graph or summaries |
| Build | 2/5 | `@latest` tools, hidden errors, stale coverage, duplicated with `Makefile` |
| Tests | 4/5 | Thorough. Heavy mocking |

---

## 2. Findings

Severity: **P0** fix now · **P1** fix next · **P2** should fix · **P3** clean-up. Effort: S < ½ day, M ≈ 1–2 days.

### 2.1 Correctness and parity

#### C-1 · P1 · Path segments aren't escaped — Effort S
- **Where:** `internal/client/workflow.go`, `session.go`, `controlbus.go`, `plugin.go`, `logs.go`
  build paths with `fmt.Sprintf("/api/workflow/%s/%s/node/%s/pause", sid, eid, nid)`. Query
  strings already use `url.Values`.
- **Impact:** `--node "a/../../sessions"` or an ID containing `?` or `#` changes the endpoint that
  gets called. Even for innocent IDs with spaces, you get confusing 404s.
- **Fix:** A helper, `func p(parts ...string) string { … url.PathEscape(part) … }`, used for every
  path.

#### C-2 · P1 · There's no timeout on non-streaming requests — Effort S
- **Where:** `NewClient` uses `http.Client{Timeout: 0}` for all calls.
- **Fix:** Add `--timeout` (default 30 s), applied with `context.WithTimeout` for normal requests.
  Streams keep using the command's cancel context (Ctrl-C).

#### C-3 · P2 · No auth support — Effort S
- When `web` adds an API key (W1), every CLI call fails.
- **Fix:** `--token` / `YUKTA_TOKEN` → `Authorization: Bearer …`, set in `newRequestWithBody`.

#### C-4 · P2 · `workflow start` can't send a payload — Effort S
- The server now accepts `payload` on `POST /api/workflow/start` (uncommitted change). The CLI
  sends only `sessionId` and `workflowId`.
- **Fix:** `--payload '{"k":"v"}'` or `--payload-file` (the commands take positional IDs, so a flag fits best).

#### C-5 · P3 · No commands for newer endpoints — Effort S
- These have no CLI command: `/api/sessions/summaries`,
  `/api/sessions/{sid}/workflows/{wid}/graph`, `/api/sessions/{sid}/workflows/{wid}/executions`,
  and the structured log stream `/logs/entries`.
- **Fix:** Add them as needed. Generating the client from the server's OpenAPI document
  (`/v3/api-docs`) would prevent future drift.

### 2.2 Build

| ID | Finding | Fix | Effort |
|---|---|---|---|
| C-6 | `go run …golangci-lint@latest`, `gosec@latest` and `govulncheck@latest` during `check`: non-reproducible, and executes unpinned upstream code. The v1 module path for golangci-lint may also not support Go 1.26, and `.golangci.yaml` is in v1 format | Pin the versions with a `tools.go` / Go 1.24+ `tool` directive in `go.mod` (`go tool golangci-lint`). Migrate the config to the v2 format if you upgrade | S |
| C-7 | `goBuild`, `goBuildAll`, `goVet` and `goModTidy` start processes without reading stdout or stderr. Compiler errors aren't shown, and large output could block the process | Use Gradle `Exec` tasks (output is inherited), or `redirectErrorStream(true)` plus `inheritIO()` | S |
| C-8 | `goCoverageCheck` reuses an existing `coverage.out`, which may be stale, so outdated coverage can pass. Its YAML is parsed with regexes | Always regenerate, or make it depend on `goTestCoverage` with inputs and outputs. Read thresholds with a small Go program, or keep them in Gradle | S |
| C-9 | About 400 lines of Kotlin duplicate the `Makefile` targets | Let Gradle call `make <target>` through `Exec` and keep the logic in one place | M |
| C-10 | `goBuild` maps every non-ARM architecture to `amd64` | Use `go env GOARCH`, or leave GOOS and GOARCH unset for native builds | S |

### 2.3 Simplification

| ID | Finding | Fix | Effort |
|---|---|---|---|
| C-11 | `RequestFactory`, `DefaultRequestFactory` and the `HTTPDoer` interface exist only for tests, plus a 612-line `client/mock.go` | Test against `httptest.NewServer`. Delete the interfaces and the mock (about 650 lines) | M |
| C-12 | `NewClient("")` in `app.Run`, then `PersistentPreRun` overwrites `BaseURL` | Create the client after the flags are parsed | S |
| C-13 | Each Cobra command lives in its own file of about 40 lines, with nearly identical bodies (pause, resume, step enable and disable…) | A table-driven helper `simpleAction(use, short, fn)` would remove about 400 lines | S |

---

## 3. Roadmap

| Phase | Items | Outcome |
|---|---|---|
| **1. Safe requests** (½ day) | C-1, C-2 | Correct endpoints and no hangs |
| **2. Parity** (1 day) | C-3, C-4, C-5 | Works with an authenticated server and payloads |
| **3. Reproducible build** (1 day) | C-6, C-7, C-8, C-10 | Pinned tools and visible errors |
| **4. Trim** (1 day) | C-9, C-11, C-12, C-13 | About 1.5k fewer lines |
