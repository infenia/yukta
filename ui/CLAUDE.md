# UI Module CLAUDE.md

## Overview
React SPA frontend with Vite, Tailwind CSS v4, and Gradle integration. The `ui` module produces a static JAR containing the built React application, consumed by the `boot` module for deployment.

## Project Structure
- **Root level**: Node/Vite/React project files (package.json, vite.config.ts, src/, e2e/, index.html, public/)
- **`docs/`**: `ui-module-guide.html` (interactive guide) and `ui-improvements.md` (review findings). The design spec and mockups (`docs/design/`) referenced below no longer exist
- **`build.gradle.kts`**: Gradle wiring for Vite build → JAR static assets
- **`build/`**: Gradle build output (JAR, reports, etc.)

## Build & Development Commands

### Development
```bash
# Terminal 1: Vite dev server (http://localhost:5173 with HMR)
cd ui && pnpm dev

# Terminal 2: Spring Boot backend (http://localhost:8080)
./gradlew :boot:bootRun
```

### Production Build
```bash
# Build the React SPA and create JAR with static assets
./gradlew :ui:build
```

### Testing
```bash
# Unit/component tests (Vitest + React Testing Library)
./gradlew :ui:pnpmTest

# E2E tests (Playwright, requires browsers installed)
./gradlew :ui:pnpmTestE2e -PrunE2e=true
```

### Test Coverage Reporting
```bash
# Generate coverage report and auto-open in browser (line + branch coverage)
pnpm coverage

# Generate coverage without opening (useful for CI/CD)
pnpm exec vitest run --coverage
```

The report includes:
- **Line coverage**: Which lines were/weren't executed
- **Branch coverage**: Which conditional branches were/weren't taken
- **Interactive HTML**: Click any file to see source code with coverage highlighting
- **Metrics**: Per-file and aggregate coverage statistics

Report location: `coverage/index.html` (generated, git-ignored)

## Tech Stack
- **React 19**: UI framework
- **Vite 8**: Fast dev server and production build (Rolldown; vendor chunks in `vite.config.ts`)
- **React Router 8**: Client-side routing (createBrowserRouter, no SSR)
- **Tailwind CSS 4**: CSS-first `@theme` design tokens (light + dark modes)
- **TanStack Query 5**: Server state & data fetching
- **TanStack Table 9**: Headless data table logic
- **TanStack Virtual**: Windowed rendering of the log panel
- **shadcn/ui**: Copy-in primitives, style `base-rhea`. Re-adding one with `pnpm dlx shadcn@latest add <name> -o` needs three fixes: the `cn` import (`@/lib/utils`), Material Symbols instead of `lucide-react`, and the SPDX header
- **TypeScript strict mode**: Type safety
- **Vitest + RTL**: Unit/component testing
- **Playwright**: E2E browser testing
- **ESLint (flat config) + Prettier**: Linting & formatting
- **pnpm**: Package manager (Gradle-managed Node version via node-conventions)

## Architecture

### Gradle Integration
- `pnpmBuild` task: Runs `pnpm run build` (working directory: ui root) → produces `dist/`
- `processResources`: Copies `dist/` → `build/resources/main/static/` → included in JAR
- Static assets served by `boot` module via Spring's classpath resource handling
- SpaWebFluxConfig in boot: Fallback routing (non-existent routes → index.html for SPA)

### Frontend Architecture
- **Route-based** (`routes/router.tsx`, all but the list are `lazy`): `/` (session list; `/sessions` redirects here), `/sessions/new`, `/sessions/:sessionId`, `/sessions/:sessionId/workflow/:workflowId` (run view with `RunControls` and per-node menus), `.../workflow/:workflowId/edit` (builder), `/plugins` (catalog with config-field table and canvas preview), `/history` (recent runs per session), `/control` (control-bus nodes and heartbeats). The header links Sessions, Plugins, History and Control
- **Builder** (`features/builder`): form-based, not drag-and-drop. `WorkflowEditorPage` edits one workflow draft (add node, edges via selects, live canvas preview), `NodeInspector` renders the plugin's `configSchema` through `ConfigForm` (raw JSON editor when a plugin declares none) plus the shared `engine` policy panel. Save validates with `POST /api/sessions/validate`, then sends the whole session (`GET /api/sessions/{id}/config` keeps tag values) to `POST /api/sessions`
- **Feature-based folders**: `src/features/sessions/{api,components,hooks,types}` for scalability
- **Shared UI**: `src/components/ui/` (shadcn primitives), `src/components/layout/` (AppHeader, AppFooter, etc.)
- **Lib**: `src/lib/apiClient.ts` (`fetchApi` wrapper and the `api` URL-encoding template tag), `src/lib/queryClient.ts`, `src/lib/theme.ts` (dark mode)
- **Design tokens**: `src/index.css` Tailwind v4 `@theme` block

### Data Fetching
- REST: TanStack Query for session list, plugin metadata, workflow configs
- SSE: `useEventSourceJson` streams execution status (`GET /api/workflow/{sessionId}/status/{executionId}/stream`) and logs (`GET /api/sessions/{sessionId}/executions/{executionId}/logs/entries`). It reconnects with backoff (1 s to 30 s) while the execution is live; the server replays history, so consumers dedupe
- API client unwraps the `ApiResponse<T>` envelope and throws `ApiError` carrying the server's `message` and `FieldError[]`. Build URLs with ``api`/api/sessions/${id}` `` so IDs are encoded
- Server statuses are mapped only in `features/workflow/lib/statusMapper.ts`. `NODE_*` values are node-level, never execution-terminal

## Design System
- **Tokens**: `src/index.css`. shadcn preset `b27GcrRo` (`base-rhea`: soft, rounded, Inter) sets the structure and shape. The colours come from `public/logo.svg`: blue `#2F6BFF` (primary), slate `#6A7A9B` (cool-tinted neutrals) and orange `#E85D0C` (`--attention`, used for the running pulse). `:root` / `.dark` hold the shadcn variables (oklch); the Material-style names (`on-surface`, `surface-container`, `outline-variant`, ...) are aliases in `@theme inline`. Success, warning and error keep their own colours
- **Typography**: Inter Variable (`@fontsource-variable/inter`), JetBrains Mono for code
- **Logo and favicons**: `public/logo.svg` is the source (untouched, it embeds C2PA metadata). `pnpm run favicons` (needs Inkscape and ImageMagick) regenerates `favicon.svg` (drawing only), `favicon.ico`, `favicon-96x96.png`, `apple-touch-icon.png` and the manifest PNGs. The header shows `/favicon.svg`
- **Icons**: Material Symbols Outlined, self-hosted as a subset (`src/assets/material-symbols-subset.woff2`, about 11 KB). After adding an icon name anywhere in `src`, run `pnpm run icons` to regenerate it (needs network). Plugin node HTML may only use icons listed in `PLUGIN_ICONS` in `scripts/subset-icons.mjs`. `lucide-react` is not used
- **Corners**: the preset's radius scale (`--radius: 0.625rem`). Components from `shadcn add` are rounded; give custom containers `rounded-xl` (panels, tables, cards) or `rounded-lg` (inputs, small controls) and chips `rounded-full`
- **Semantic tokens**: `success`/`warning` for status, `terminal`/`log-*` for the logs panel (never hardcode black or `neutral-*`). Use the named type utilities (`text-headline-md`, `text-body-*`, `text-label-*`, `text-code-sm`); minimum text size is 12px
- **Shared pieces**: `PageHeader` (one h1 per page), `StateMessage` (loading/empty/error with retry), `CodeBlock` (mono JSON with copy), `StatusChip` (dot + label)
- **Layout**: pages are full width (no `max-w-*` on `<main>`); the header, breadcrumb, pages and footer share one gutter (`px-4 sm:px-6`). Only prose (`max-w-prose`) and forms (`max-w-xl`) keep a readable measure. Spacing tokens are `--spacing-spacing-*` (class `px-spacing-md`) so they do not override Tailwind's `max-w-md`/`w-lg` named sizes
- **Plugin categories**: `--cat-trigger|processor|terminal` (the logo's orange, blue and slate) are used only on the canvas, the plugin catalog and edges (`features/workflow/lib/categoryAccent.ts`). Node effects (running glow, edge flow, hover, selected ring, graph fade-in) live in `index.css` under "Workflow canvas" and are disabled by `prefers-reduced-motion`
- **Icon names missing from `material-symbols`** (`expand_more`, `expand_less`) are listed in `PLUGIN_ICONS` in `scripts/subset-icons.mjs`
- **Bundle budget**: `pnpm run build` fails if a JS chunk is over 300 KB or the icon font over 100 KB (`scripts/check-bundle-size.js`)

## Development Workflow
1. **Frontend dev**: `cd ui && pnpm dev` (Vite dev server auto-rebuilds, HMR enabled)
2. **Backend dev**: `./gradlew :boot:bootRun -x test` (proxies `/api/**` to Spring via vite.config.ts server.proxy)
3. **Tests**: `pnpm test` (watch mode) or `pnpm test:e2e` (Playwright against dev server)
4. **Production**: `./gradlew :ui:build` wires Vite output into JAR; `boot` serves it as static resources

## Native Image Support
- `boot` module includes GraalVM native-image configuration
- `ui` JAR static assets (dist/) are copied into classpath resources at build time
- SpaWebFluxConfig provides SPA fallback routing (resource resolving is native-image compatible)
- No dynamic reflection, no classloader resource enumeration needed

## License Header Management

All UI source files include SPDX license headers. This is managed through a **three-layer approach**:

### 1. **REUSE.toml** (Baseline Compliance)

- FSFE-compliant metadata at repo root
- Defines copyright holder, license, and excludes generated files (dist/, node_modules/)
- Validated by `reuse lint` (run via CI/CD)

### 2. **Gradle Task** (`./gradlew :ui:check`)

- `validateHeaders` task runs on every `check`
- Calls `pnpm run lint:headers` (Node.js-based glob validation)
- Checks all source files match expected SPDX header format
- Fails the build if headers are missing/incorrect

### 3. **Header Templates** (`config/license/header-js.txt`, `config/license/header-css.txt`)

- `header-js.txt`: For JS/TS source files (double-slash comment syntax)
- `header-css.txt`: For CSS source files (block comment syntax)
- Used by developers to format headers consistently

**Header Format:**

```typescript
// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
```

**Quick Commands:**

```bash
# Validate headers locally
pnpm run lint:headers

# Add/fix headers manually to new files
cat config/license/header-js.txt | cat - <newfile> > <newfile>.tmp && mv <newfile>.tmp <newfile>

# Gradle validation (runs on every check)
./gradlew :ui:check
```

## Imports
@.claude/rules/coding-standards.md
@.claude/rules/git-workflow.md
