// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { defineConfig, devices } from '@playwright/test';

/**
 * Playwright Configuration with MSW Integration
 *
 * This configuration:
 * - Runs tests in parallel across all browsers
 * - Uses MSW for API mocking (no real backend calls)
 * - Captures traces and screenshots on failure
 * - Supports both CI/CD and local development workflows
 *
 * @see https://playwright.dev/docs/test-configuration
 */
export default defineConfig({
  testDir: './e2e/tests',
  testMatch: '**/*.spec.ts',

  // Execution
  fullyParallel: true,
  forbidOnly: !!process.env['CI'],
  retries: process.env['CI'] ? 2 : 0,
  workers: process.env['CI'] ? 1 : undefined,

  // Timeout configuration (CI needs longer timeouts due to resource constraints)
  timeout: process.env['CI'] ? 60 * 1000 : 30 * 1000,
  expect: { timeout: process.env['CI'] ? 10000 : 5000 },

  // Screenshot comparison (organized by test file name for better maintainability)
  snapshotDir: 'e2e/__snapshots__',
  snapshotPathTemplate: '{snapshotDir}/{testFileName}/{arg}-{projectName}-{platform}{ext}',

  // Reporting
  reporter: [
    ['html'],
    ['json', { outputFile: 'test-results/results.json' }],
    ['junit', { outputFile: 'test-results/junit.xml' }],
    ...(process.env['CI'] ? [['github'] as const] : []),
  ],

  use: {
    // Base URL for all requests
    baseURL: process.env['BASE_URL'] || 'http://localhost:5173',

    // Screenshot and trace configuration
    screenshot: 'only-on-failure',
    trace: 'on-first-retry',

    // Video recording (optional, can be slow)
    // video: 'retain-on-failure',

    // Action timeout for clicks, fills, etc. (CI needs longer timeouts)
    actionTimeout: process.env['CI'] ? 20 * 1000 : 10 * 1000,

    // Navigation timeout (CI needs longer timeouts)
    navigationTimeout: process.env['CI'] ? 60 * 1000 : 30 * 1000,
  },

  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chromium'] },
    },
    {
      name: 'firefox',
      use: { ...devices['Desktop Firefox'] },
    },
    {
      name: 'webkit',
      use: { ...devices['Desktop Safari'] },
    },

    // Optional: Mobile testing
    // {
    //   name: 'mobile-chrome',
    //   use: { ...devices['Pixel 5'] },
    // },
    // {
    //   name: 'mobile-safari',
    //   use: { ...devices['iPhone 12'] },
    // },
  ],

  // Dev server configuration
  webServer: {
    command: 'pnpm dev',
    url: 'http://localhost:5173',
    reuseExistingServer: !process.env['CI'],
    timeout: 120 * 1000,
  },

  // Global setup/teardown
  globalSetup: './e2e/playwright.global-setup.ts',
});
