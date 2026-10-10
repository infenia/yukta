# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited

# ui — Review, Improvements & Optimisations

> Scope: `ui/src` (React 19 + Vite + Tailwind 4; ≈4k non-test lines) reviewed on 2026-10-09 against `main` @ `8d3161b5`,
> including a look at the current `dist/` build output.
> Companion: [`ui-module-guide.html`](ui-module-guide.html) is the interactive guide.

## 1. Verdict

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

| Area | Score | Notes |
|---|---|---|
| Architecture | 4.5/5 | Clean feature structure, typed API layer |
| Tests | 4.5/5 | About 98% unit coverage plus E2E baselines |
| Performance | 2/5 | 4 MB icon font, 887 KB single JS chunk, O(n) log append per line |
| Resilience | 2/5 | No SSE reconnect. Error messages lost |
| Contract alignment | 2.5/5 | Hand-maintained status and response types drift from the server |
| Security | 4/5 | DOMPurify on plugin HTML. IDs not URL-encoded |

---

## 2. Findings

Severity: **P0** fix now · **P1** fix next · **P2** should fix · **P3** clean-up. Effort: S < ½ day, M ≈ 1–2 days.

### 2.1 Performance

#### U1 · P1 · A 3.96 MB icon font for a few dozen icons — Effort S
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
- **Where:** `dist/assets/index-*.js` is 887,435 bytes. The routes aren't lazy.
- **Fix:** Use `lazy(() => import(...))` per route in `routes/router.tsx` so React Flow and dagre
  only load on the workflow page. Add
  `build.rollupOptions.output.manualChunks` for `react`, `@xyflow/react` and `@tanstack/*`. Keep a
  size budget in CI (for example `vite build` plus a check that the largest chunk is under 300 KB).

#### U3 · P2 · Log streaming copies the whole buffer on every line — Effort S
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
- **Where:** `hooks/useEventSourceJson.ts`: `onerror` calls `source.close()` and sets
  `status: 'error'`.
- **Impact:** A server restart, a proxy idle timeout (the server sends no heartbeat, web W9) or
  Wi-Fi switching ends live progress and logs for good, unless the user reloads.
- **Fix:** Reconnect with backoff (1 s, 2 s, 4 s, up to 30 s) while the execution isn't terminal.
  After web W9 adds event IDs, track `lastEventId` so the server can resume. Until then, dedupe
  replayed history by `(timestamp, nodeId, line)`.

#### U5 · P1 · Stopping one node makes the UI treat the execution as finished — Effort S
- **Where:** `statusMapper.ts`: `TERMINAL_EXEC_STATUSES` includes `NODE_STOPPED`, and
  `EXEC_STATUS_MAP` maps it to `stopped`. Core writes `NODE_STOPPED` as the *workflow* status
  when a single node is stopped (core C1).
- **Fix:** Short term, remove `NODE_STOPPED` (and the other `NODE_*` values) from the
  execution-terminal set. Long term, after core S1, generate the status union type from the
  server's OpenAPI enum.

#### U6 · P1 · `fetchApi` shows the wrong message, mistypes errors, and can drop headers — Effort S
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

---

## 3. Roadmap

| Phase | Items | Outcome |
|---|---|---|
| **1. Weight loss** (1 day) | U1, U2 | First load drops from about 4.9 MB to under 1 MB |
| **2. Live views that survive** (1 day) | U4, U5, U3 | Progress and logs keep updating, smoothly |
| **3. Honest errors** (½ day) | U6, U7 | Users see the server's real message |
| **4. Contract** (1–2 days) | U8, U9, U10, U11, U12 | Types generated from the server, tidy dependencies, CSP |
