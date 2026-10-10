# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited

# ui — Review, Improvements & Optimisations

> Scope: `ui/src` (React 19 + Vite + Tailwind 4; ≈4k non-test lines) reviewed on 2026-10-09 against `main` @ `8d3161b5`,
> including a look at the current `dist/` build output.
> **Status (2026-10-10): all twelve findings (U1–U12) are resolved.** Each finding below keeps its original
> write-up and adds a **Resolved** note saying what was done.
> Companion: [`ui-module-guide.html`](ui-module-guide.html) is the interactive guide.

## 1. Verdict

The text of this section is the state at review time; see the resolution notes in section 2 and the
updated scorecard.

The UI is a modern, well-tested SPA:

- Feature folders (`sessions`, `workflow`) with separate `api/`, `hooks/` and `components/`.
- TanStack Query for fetching, React Flow with dagre for the DAG canvas, and SSE hooks for live
  progress and logs.
- shadcn/Base UI primitives and Tailwind 4 design tokens with dark mode.
- Vitest at about 98% coverage, and Playwright E2E with screenshot baselines.
- Plugin-provided node HTML is sanitised with DOMPurify before being injected, which is good.

The gaps:

- **Payload weight.** The app ships a **3.96 MB** Material Symbols font (the whole icon set) for
  icons used in 18 files, alongside `lucide-react`. All JavaScript is a single **887 KB** chunk,
  with no route-level code splitting.
- **Live views stop silently.** `useEventSourceJson` closes the `EventSource` on the first error
  and never reconnects. Combined with the server's lack of SSE heartbeats (web W9), any proxy idle
  timeout or network blip freezes the progress and log panels.
- **API error handling shows the wrong text and can drop headers.** `fetchApi` shows
  `json.error` (the reason phrase, "Not Found") instead of `json.message`. It types `errors` as
  `Record<string, string[]>`, but the server sends a list of `{field, message}`. It also spreads
  `...options` after the merged headers, so passing any `headers` drops `Content-Type`.
- **The status vocabulary is guessed.** `statusMapper` includes statuses the backend never emits
  (`COMPLETED`, `FAILED`, `CANCELLED`, `NODE_SKIPPED`). It also treats **`NODE_STOPPED` as a
  terminal execution status**, so stopping one node makes the UI consider the whole run finished.

### Scorecard

| Area | At review | Now | Notes |
|---|---|---|---|
| Architecture | 4.5/5 | 4.5/5 | Clean feature structure, typed API layer |
| Tests | 4.5/5 | 4.5/5 | Unit tests plus E2E baselines; new tests for every change |
| Performance | 2/5 | 4.5/5 | 11 KB icon font, largest JS chunk 273 KB, batched and virtualised logs |
| Resilience | 2/5 | 4.5/5 | SSE reconnects with backoff, server error messages reach users |
| Contract alignment | 2.5/5 | 4/5 | Types generated from OpenAPI, drift guard in the build. Statuses stay strings until core S1 |
| Security | 4/5 | 4.5/5 | CSP on index.html, IDs URL-encoded |

---

## 2. Findings

Severity: **P0** fix now · **P1** fix next · **P2** should fix · **P3** clean-up. Effort: S < ½ day, M ≈ 1–2 days.

### 2.1 Performance

#### U1 · P1 · A 3.96 MB icon font for a few dozen icons — Effort S
- **Resolved:** src/assets/material-symbols-subset.woff2 (11 KB, down from 3.96 MB) holds only the icons the UI and the built-in plugins use, at the one axis setting index.css applies. `pnpm run icons` regenerates it from Google Fonts (scripts/subset-icons.mjs scans src for icon names; plugin icons are listed in PLUGIN_ICONS). lucide-react is removed; the breadcrumb uses Material Symbols. The build fails if the icon font exceeds 100 KB.
- **Where:** `src/index.css` has `@import 'material-symbols/outlined.css'`. `dist/assets` contains
  `material-symbols-outlined-*.woff2` at **3,960,036 bytes**. `lucide-react` is also a dependency.
- **Impact:** Every first visit downloads about 4 MB before icons render. That's over 80% of the
  whole `dist/`.
- **Fix:** `CLAUDE.md` records Material Symbols as a deliberate choice (to match the mockups pixel
  for pixel), so keep it but **subset** it. Generate a woff2 that holds only the glyphs used, with
  `fonttools`/`pyftsubset` at build time or the Google Fonts `icon_names=` parameter. Then drop
  `lucide-react` if it's no longer needed. Plugin `UiDesign` HTML uses `material-symbols-outlined` class
  names. Replace those with an icon name that the UI maps (plugin-api A13, plugins G22).

#### U2 · P1 · A single 887 KB JS chunk — Effort S
- **Resolved:** The session-details and workflow-details routes are lazy (`lazy` in routes/router.tsx), so React Flow and dagre load only on the workflow page. vite.config.ts splits react, tanstack, router and ui-kit vendor chunks. The largest chunk is 273 KB, and `scripts/check-bundle-size.js` (run by `pnpm run build`) fails above 300 KB. First load is about 770 KB uncompressed (about 230 KB gzipped).
- **Where:** `dist/assets/index-*.js` is 887,435 bytes. The routes aren't lazy.
- **Fix:** Use `lazy(() => import(...))` per route in `routes/router.tsx` so React Flow and dagre
  only load on the workflow page. Add
  `build.rollupOptions.output.manualChunks` for `react`, `@xyflow/react` and `@tanstack/*`. Keep a
  size budget in CI (for example `vite build` plus a check that the largest chunk is under 300 KB).

#### U3 · P2 · Log streaming copies the whole buffer on every line — Effort S
- **Resolved:** useExecutionLogs buffers incoming lines and flushes once every 100 ms (LOG_FLUSH_MS), so a burst is one state update. LogsPanel renders through @tanstack/react-virtual with fixed 20 px rows, so only the visible window of the 2,000-line buffer is in the DOM.
- **Where:** `useExecutionLogs` does `setEntries(prev => [...prev, entry])` with up to 2,000
  entries, and re-renders once per line. `LogsPanel` renders every visible entry, with no
  virtualisation.
- **Impact:** Bursty logs (process output) mean O(n²) copying and up to 2,000 DOM rows. The tab
  stutters.
- **Fix:** Buffer incoming entries in a ref and flush once per animation frame or every 100 ms.
  Render the list with virtualisation (`@tanstack/react-virtual` fits the TanStack stack already in
  use).

### 2.2 Resilience and correctness

#### U4 · P1 · SSE views never reconnect — Effort S
- **Resolved:** useEventSourceJson reopens a failed stream with exponential backoff (1 s doubling to 30 s, reset when a connection opens) while its `reconnect` flag is true. The progress stream stops with the execution (its URL becomes null once terminal). The log stream keeps retrying while the execution is not terminal, and drops the history the server replays on each connection by (timestamp, plugin, stream, message). A new EventSource does not send Last-Event-ID, so replay-and-dedupe is used instead of resume.
- **Where:** `hooks/useEventSourceJson.ts`: `onerror` calls `source.close()` and sets
  `status: 'error'`.
- **Impact:** A server restart, a proxy idle timeout (the server sends no heartbeat, web W9) or
  Wi-Fi switching ends live progress and logs for good, unless the user reloads.
- **Fix:** Reconnect with backoff (1 s, 2 s, 4 s, up to 30 s) while the execution isn't terminal.
  After web W9 adds event IDs, track `lastEventId` so the server can resume. Until then, dedupe
  replayed history by `(timestamp, nodeId, line)`.

#### U5 · P1 · Stopping one node makes the UI treat the execution as finished — Effort S
- **Resolved:** NODE_STOPPED is no longer terminal and maps to `running`, because core writes it as the workflow status while the other nodes keep going (a regression test covers it). The other NODE_* values were never in the terminal set. The statuses are free-form strings in the OpenAPI document, so a generated status union is not possible until core S1 adds an enum.
- **Where:** `statusMapper.ts`: `TERMINAL_EXEC_STATUSES` includes `NODE_STOPPED`, and
  `EXEC_STATUS_MAP` maps it to `stopped`. Core writes `NODE_STOPPED` as the *workflow* status
  when a single node is stopped (core C1).
- **Fix:** Short term, remove `NODE_STOPPED` (and the other `NODE_*` values) from the
  execution-terminal set. Long term, after core S1, generate the status union type from the
  server's OpenAPI enum.

#### U6 · P1 · `fetchApi` shows the wrong message, mistypes errors, and can drop headers — Effort S
- **Resolved:** fetchApi spreads `...options` first and merges headers last, so Content-Type survives. Errors use `json.message` (falling back to the status text when the body is not JSON), `errors` is typed `FieldError[]`, success is decided by `response.ok` (204 returns undefined), and a non-JSON success body is a clear error.
- **Where:** `lib/apiClient.ts`.
  1. `new ApiError(json.status, json.error, …)`: `error` is the reason phrase, so users see
     "Bad Request" instead of `json.message` ("Validation failed: …").
  2. `errors?: Record<string, string[]>`, but the server sends `FieldError[]` (`{field, message}`).
  3. `{ headers: {...}, ...options }`: if `options.headers` is set, it replaces the merged object,
     and `Content-Type` is lost.
  4. Non-JSON error bodies (a proxy 502 page, for example) become "Failed to parse JSON".
  5. Only 200, 201 and 202 count as success. A 204 fails.
- **Fix:**
  ```ts
  const res = await fetch(path, { ...options, headers: { 'Content-Type': 'application/json', ...options?.headers } });
  const json = await res.json().catch(() => null);
  if (!res.ok) throw new ApiError(res.status, json?.message ?? res.statusText, json?.errors);
  return json?.data as T;
  ```

#### U7 · P2 · IDs aren't URL-encoded — Effort S
- **Resolved:** A new `api` tagged template (lib/apiClient.ts) applies encodeURIComponent to every interpolated value. All seven call sites use it.
- **Where:** `getWorkflowGraph`, `getWorkflowProgress`, `getWorkflowExecutions`,
  `getSessionWorkflows`, `getSessionDetails`, `useExecutionLogs` and `useExecutionProgress` build
  `` `/api/sessions/${sessionId}/…` ``.
- **Fix:** A small `api\`…\`` tagged template that applies `encodeURIComponent` to each value.

### 2.3 Contract alignment and dependencies

| ID | Finding | Fix | Effort |
|---|---|---|---|
| U8 | Hand-written TS types for every response (`types/workflow.ts`, the session types, `ApiResponse`) drift from the server (U5, U6) | Generate the types from `/v3/api-docs` (springdoc is already on the server) with `openapi-typescript`, and fail CI if the generated file changes | M |
| U9 | Build-time tools in `dependencies`: `shadcn` (a CLI), `tailwindcss`, `@tailwindcss/vite` | Move them to `devDependencies` | S |
| U10 | `package.json` is `name: "frontend"`, `version: "0.0.0"` | `yukta-ui`, with the version synced from Gradle | S |
| U11 | The SPA serves `dist/` with a one-year cache for `/assets/**` (correct, hashed) but `index.html` only gets `no-cache`. There's no CSP header for the injected plugin HTML | Add a `Content-Security-Policy` on `index.html` (boot): `default-src 'self'; style-src 'self' 'unsafe-inline'` | S |
| U12 | `CLAUDE.md` is stale: it lists TanStack Table 8 (now 9.1.2, #248) and Vite "7+" (8.x is installed); it refers to `ui/docs/design/DESIGN.md` and mockups that no longer exist; it calls SSE "future" (it's implemented); and it says `cd frontend` | Update the docs | S |

**Resolutions (U8–U12)**

| ID | Resolved |
|---|---|
| U8 | `pnpm run api:types` writes src/types/api.generated.ts from /v3/api-docs (openapi-typescript); `-- --check` fails when the committed file is stale, for CI against a booted server. A compile-time test (types/__tests__/apiContract.test.ts) fails the type check (`tsc -p tsconfig.vitest.json`, part of `pnpm run build`) when a hand-written UI type and its server schema stop having the same fields. The hand-written types stay as the working types because the generated ones mark every field optional. Also fixed on the way: boot's SPA fallback was serving index.html for /v3/** and Swagger UI. |
| U9 | shadcn, tailwindcss and @tailwindcss/vite moved to devDependencies (material-symbols too, as it is now only read by the icon script). |
| U10 | package.json is `yukta-ui` at 0.0.1-SNAPSHOT. The `verifyPackageVersion` Gradle task, part of `:ui:check`, fails when it differs from the Gradle project version. |
| U11 | SpaWebFluxConfig sends `Content-Security-Policy: default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self' data:; object-src 'none'; base-uri 'self'; frame-ancestors 'none'` with index.html. To make that workable, the inline theme script moved to /theme-init.js and the unused Google Fonts links were removed. |
| U12 | ui/CLAUDE.md now lists TanStack Table 9 and Vite 8, drops the missing design spec and mockups, describes the implemented SSE hooks, the icon subset and the bundle budget, and says `cd ui`. |

---

## 3. Roadmap

| Phase | Items | Outcome | Status |
|---|---|---|---|
| **1. Weight loss** (1 day) | U1, U2 | First load drops from about 4.9 MB to under 1 MB (now about 770 KB uncompressed) | Done |
| **2. Live views that survive** (1 day) | U4, U5, U3 | Progress and logs keep updating, smoothly | Done |
| **3. Honest errors** (½ day) | U6, U7 | Users see the server's real message | Done |
| **4. Contract** (1–2 days) | U8, U9, U10, U11, U12 | Types generated from the server, tidy dependencies, CSP | Done |
