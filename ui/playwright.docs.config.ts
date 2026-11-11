// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { defineConfig, devices } from '@playwright/test';

/**
 * Config of the documentation screenshots only (`pnpm docs:screenshots`). The normal
 * `playwright test` (playwright.config.ts, testDir ./e2e/tests) never picks these up. Stubs are
 * page.route() calls against the vite dev server, so no backend is needed.
 */
export default defineConfig({
  testDir: './e2e/docs-screenshots',
  testMatch: 'capture.spec.ts',
  fullyParallel: true,
  workers: process.env['CI'] ? 1 : 4,
  retries: 0,
  timeout: 60 * 1000,
  reporter: 'list',
  use: {
    baseURL: process.env['BASE_URL'] || 'http://localhost:5173',
    ...devices['Desktop Chromium'],
    viewport: { width: 1440, height: 900 },
  },
  projects: [{ name: 'docs-screenshots' }],
  webServer: {
    command: 'pnpm dev',
    url: 'http://localhost:5173',
    reuseExistingServer: true,
    timeout: 120 * 1000,
  },
});
