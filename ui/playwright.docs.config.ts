// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { defineConfig, devices } from '@playwright/test';

/**
 * Config of the documentation screenshots only (`pnpm docs:screenshots`). The normal
 * `playwright test` (playwright.config.ts, testDir ./e2e/tests) never picks these up. Stubs are
 * page.route() calls against the vite dev server, so no backend is needed.
 *
 * DOCS_SHOT_PORT picks the dev server's port (default 5173). A server already on that port is
 * reused, so when another app holds 5173, pass another port or every shot captures that app.
 */
const port = process.env['DOCS_SHOT_PORT'] || '5173';
const baseURL = process.env['BASE_URL'] || `http://localhost:${port}`;

export default defineConfig({
  testDir: './e2e/docs-screenshots',
  // DOCS_HERO=1 (run.mjs --hero): the README's animated run instead of the screenshots.
  testMatch:
    process.env['DOCS_HERO'] === '1' ? 'hero.spec.ts' : 'capture.spec.ts',
  fullyParallel: true,
  workers: process.env['CI'] ? 1 : 4,
  retries: 0,
  timeout: 60 * 1000,
  reporter: 'list',
  use: {
    baseURL,
    ...devices['Desktop Chromium'],
    viewport: { width: 1440, height: 900 },
  },
  projects: [{ name: 'docs-screenshots' }],
  webServer: {
    command: `pnpm dev --port ${port} --strictPort`,
    url: baseURL,
    reuseExistingServer: true,
    timeout: 120 * 1000,
  },
});
