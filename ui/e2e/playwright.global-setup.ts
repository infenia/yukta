// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { chromium } from '@playwright/test';

/**
 * Global setup hook for Playwright
 *
 * This runs once before all tests and can be used to:
 * - Start the MSW server
 * - Seed test databases
 * - Verify API endpoints are reachable
 * - Set up environment variables
 *
 * @see https://playwright.dev/docs/test-global-setup-teardown
 */
async function globalSetup() {
  console.log('🔧 Running global setup...');

  const baseUrl = process.env.BASE_URL || 'http://localhost:5173';

  // Verify that the dev server is running
  const browser = await chromium.launch();
  const page = await browser.newPage();

  try {
    const response = await page.goto(baseUrl, {
      waitUntil: 'networkidle',
      timeout: 30000,
    });
    if (!response?.ok()) {
      throw new Error(
        `Dev server returned status ${response?.status()} at ${baseUrl}`
      );
    }
    console.log(`✅ Dev server is running at ${baseUrl}`);
  } catch (error) {
    console.error(
      `❌ Failed to verify dev server at ${baseUrl}. Make sure "pnpm dev" is running.`
    );
    await browser.close();
    throw error;
  }

  await browser.close();
  console.log('✅ Global setup complete');
}

export default globalSetup;
