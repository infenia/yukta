# Test Coverage Reporting Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enable developers to run `pnpm coverage` to generate a line + branch coverage report and auto-open it in their browser for reviewing missing test coverage.

**Architecture:** Add a cross-platform coverage script that runs Vitest with v8 coverage provider (already configured), generates HTML reports in the `coverage/` directory, and opens the report automatically. The Vitest config already has coverage enabled with all necessary reporters; we only need to add the script and the `open` package dependency for cross-platform browser launching.

**Tech Stack:** Vitest 4.1.10 (v8 coverage provider), `open` npm package (cross-platform browser launcher)

## Global Constraints

- Vitest v8 coverage provider is already configured in `vitest.config.ts` with html, json, lcov, json-summary reporters
- Coverage thresholds are enforced at 100% (lines, functions, branches, statements)
- Report generated to `coverage/` directory (git-ignored, generated artifact)
- pnpm is the package manager (managed by Gradle node-conventions)
- License headers required on all new/modified source files (SPDX Apache-2.0)

---

## Task 1: Add `open` Package Dependency

**Files:**
- Modify: `package.json`

**Interfaces:**
- Produces: `open` package (^9.0.0 or later) available for import in scripts

- [ ] **Step 1: Add `open` as a devDependency**

Edit `package.json` to add the `open` package under `devDependencies`. The `open` package provides cross-platform browser launching (works on macOS, Linux, Windows).

Add this line to the `devDependencies` object (maintain alphabetical order):
```json
"open": "^9.1.0",
```

Current `devDependencies` section should have this added alphabetically:
```json
"devDependencies": {
  "@eslint/js": "^10.0.1",
  ...existing entries...
  "open": "^9.1.0",
  ...rest of entries...
}
```

- [ ] **Step 2: Verify the edit**

Check that `package.json` is valid JSON and `open` is in alphabetical order among devDependencies.

- [ ] **Step 3: Commit the dependency addition**

```bash
cd /media/arun/Infenia/Infenia/Development/Public/yukta/ui
git add package.json
git commit -m "feat(ui): add open package for cross-platform coverage report launching"
```

---

## Task 2: Create Coverage Launch Script

**Files:**
- Create: `scripts/open-coverage.js`

**Interfaces:**
- Consumes: `open` package (from Task 1)
- Produces: Node.js script that opens `coverage/index.html` in default browser

- [ ] **Step 1: Create the script file**

Create `scripts/open-coverage.js` with the following content. This script uses the `open` package to launch the coverage report in the default browser after tests complete.

```javascript
// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import open from 'open';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const coveragePath = path.join(__dirname, '..', 'coverage', 'index.html');

open(coveragePath).catch((err) => {
  console.error('Failed to open coverage report:', err.message);
  process.exit(1);
});
```

- [ ] **Step 2: Verify the script runs without errors**

Test that Node can parse the script:
```bash
cd /media/arun/Infenia/Infenia/Development/Public/yukta/ui
node scripts/open-coverage.js
```

Expected: Browser opens to `coverage/index.html` (or error if coverage dir doesn't exist yet, which is OK for this test). If you see "Failed to open coverage report: ENOENT" that's expected — the directory won't exist until tests run.

- [ ] **Step 3: Commit the script**

```bash
git add scripts/open-coverage.js
git commit -m "feat(ui): add script to auto-open coverage report in browser"
```

---

## Task 3: Add Coverage Script to package.json

**Files:**
- Modify: `package.json`

**Interfaces:**
- Consumes: Coverage script from Task 2, Vitest with coverage from existing config
- Produces: `pnpm coverage` command available to developers

- [ ] **Step 1: Add the coverage script**

Edit `package.json` and add a `coverage` script to the `scripts` object:

```json
"scripts": {
  "dev": "vite",
  "build": "tsc && vite build",
  "preview": "vite preview",
  "lint": "eslint .",
  "lint:headers": "node scripts/validate-headers.js",
  "test": "vitest",
  "test:e2e": "playwright test",
  "coverage": "vitest run --coverage && node scripts/open-coverage.js"
}
```

Key details:
- `vitest run` runs tests once (not watch mode)
- `--coverage` enables v8 coverage collection (already configured in vitest.config.ts)
- `&&` ensures the open script only runs if tests succeed
- `node scripts/open-coverage.js` launches the report in the browser

- [ ] **Step 2: Verify the script**

Dry-run the script (without actually running tests, just check syntax):
```bash
cd /media/arun/Infenia/Infenia/Development/Public/yukta/ui
cat package.json | grep -A 1 '"coverage"'
```

Expected output:
```
"coverage": "vitest run --coverage && node scripts/open-coverage.js"
```

- [ ] **Step 3: Commit the script addition**

```bash
git add package.json
git commit -m "feat(ui): add pnpm coverage script for test coverage reporting with auto-open"
```

---

## Task 4: Document the Coverage Workflow

**Files:**
- Modify: `CLAUDE.md`

**Interfaces:**
- Consumes: Coverage scripts from Tasks 2-3
- Produces: Documentation for developers on how to use coverage reporting

- [ ] **Step 1: Add coverage documentation section**

Open `CLAUDE.md` and locate the "### Testing" section (around line 32-39). Add a new subsection after the existing test commands:

Find this section:
```markdown
### Testing
```bash
# Unit/component tests (Vitest + React Testing Library)
./gradlew :ui:pnpmTest

# E2E tests (Playwright, requires browsers installed)
./gradlew :ui:pnpmTestE2e -PrunE2e=true
```
```

After those lines, add:

```markdown
### Test Coverage Reporting
```bash
# Generate coverage report and auto-open in browser (line + branch coverage)
pnpm coverage

# Generate coverage without opening (useful for CI/CD)
pnpm test -- --coverage
```

The report includes:
- **Line coverage**: Which lines were/weren't executed
- **Branch coverage**: Which conditional branches were/weren't taken
- **Interactive HTML**: Click any file to see source code with coverage highlighting
- **Metrics**: Per-file and aggregate coverage statistics

Report location: `coverage/index.html` (generated, git-ignored)
```

- [ ] **Step 2: Verify documentation**

Read the modified section to ensure it's clear and complete:
```bash
cd /media/arun/Infenia/Infenia/Development/Public/yukta/ui
sed -n '32,60p' CLAUDE.md
```

Should show the original testing section plus your new coverage section.

- [ ] **Step 3: Commit the documentation**

```bash
git add CLAUDE.md
git commit -m "docs(ui): add test coverage reporting documentation"
```

---

## Task 5: Verify Coverage Workflow End-to-End

**Files:**
- No new files (verification only)

**Interfaces:**
- Consumes: All tasks 1-4 completed
- Produces: Verified working coverage workflow

- [ ] **Step 1: Install dependencies**

```bash
cd /media/arun/Infenia/Infenia/Development/Public/yukta/ui
pnpm install
```

Expected: `open` package installed in node_modules, pnpm-lock.yaml updated.

- [ ] **Step 2: Run the coverage command**

```bash
pnpm coverage
```

Expected: 
- Vitest runs all tests with v8 coverage enabled
- Output shows test results and coverage summary
- `coverage/` directory created with `index.html`, `coverage-final.json`, `lcov.info`
- Browser automatically opens to `coverage/index.html`
- Report shows interactive file tree with coverage metrics

If tests fail, coverage still generates but the browser launch may fail. That's expected behavior (tests must pass before the report is useful).

- [ ] **Step 3: Inspect the generated report**

Once the browser opens, verify:
- Coverage summary is displayed at the top
- Files are listed with coverage percentages (lines, branches, functions, statements)
- Clicking a file shows the source code with line highlighting
- Green lines = covered, red lines = uncovered, yellow lines = partial branch coverage

- [ ] **Step 4: Test fallback (generate without opening)**

```bash
pnpm test -- --coverage
```

Expected: Coverage report generated to `coverage/` but browser does NOT open (no `open-coverage.js` script runs). This is useful for CI/CD environments.

- [ ] **Step 5: Verify .gitignore excludes coverage**

```bash
grep -c "coverage" .gitignore
```

Expected: Output `1` or higher (coverage directory is git-ignored). If not, coverage artifacts won't pollute commits.

- [ ] **Step 6: Final commit (if all verifications pass)**

No new files to commit in this task — all changes were in prior tasks. Just verify everything works and report success.

---

## Summary

After completing all 5 tasks:
- ✅ `open` package installed as devDependency (Task 1)
- ✅ `scripts/open-coverage.js` created for cross-platform browser launching (Task 2)
- ✅ `pnpm coverage` command added to package.json (Task 3)
- ✅ Documentation updated in CLAUDE.md (Task 4)
- ✅ End-to-end workflow verified (Task 5)

Developers can now run `pnpm coverage` to generate a comprehensive test coverage report (line + branch coverage) with interactive HTML visualization and auto-open in the browser. The report shows which lines and branches are missing test coverage, making it easy to identify gaps.
