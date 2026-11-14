// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { defineConfig, devices } from '@playwright/test';

/**
 * The UI against a REAL Yukta server (the native binary, the Docker image or bootRun): no MSW, no
 * mocked API. It creates sessions from the bundled templates and runs the examples from the browser.
 *
 *   YUKTA_URL=http://127.0.0.1:9858 pnpm test:e2e:real      (release/ui-smoke.sh does the same)
 *
 * The server needs what the examples need: YUKTA_PLUGINS_ENABLED=all and
 * YUKTA_PROCESS_ALLOWED_COMMANDS=sh,echo,python3 (see examples/README.md). A Docker server also needs
 * /tmp/yukta-examples mounted, as the examples do.
 */
export default defineConfig({
  testDir: './e2e/real',
  testMatch: '**/*.spec.ts',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 90 * 1000,
  expect: { timeout: 15 * 1000 },
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: process.env['YUKTA_URL'] || 'http://127.0.0.1:9858',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
});
