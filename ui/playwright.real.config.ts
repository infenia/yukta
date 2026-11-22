// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { defineConfig, devices } from '@playwright/test';

/**
 * The UI against a REAL Yukta server (the native binary, the Docker image or bootRun): no MSW, no
 * mocked API. It creates sessions from the bundled templates and runs the examples from the browser.
 *
 *   YUKTA_URL=http://127.0.0.1:9858 YUKTA_API_KEY=<key> pnpm test:e2e:real      (release/ui-smoke.sh does the same)
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
  // a server with the JaCoCo agent (the coverage job) answers slowly: YUKTA_E2E_EXPECT_MS gives it room
  expect: { timeout: Number(process.env['YUKTA_E2E_EXPECT_MS']) || 15 * 1000 },
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: process.env['YUKTA_URL'] || 'http://127.0.0.1:9858',
    // the ADMIN key of a server that has keys (release/e2e-server.sh key); none for an open server
    extraHTTPHeaders: process.env['YUKTA_API_KEY']
      ? { Authorization: `Bearer ${process.env['YUKTA_API_KEY']}` }
      : {},
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
});
