// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { test as base } from '@playwright/test';
import { RealApi } from './api';

/**
 * The real tier's `test`: the `api` fixture seeds through REST and cleans up after the test, and the API key
 * (YUKTA_API_KEY, see playwright.real.config.ts) is also put in the tab's sessionStorage, where the UI keeps it for
 * its own requests and for SSE.
 */
export const test = base.extend<{ api: RealApi }>({
  page: async ({ page, extraHTTPHeaders }, use) => {
    const bearer = (extraHTTPHeaders ?? {})['Authorization'] ?? '';
    const key = bearer.replace(/^Bearer /, '');
    if (key) {
      await page.addInitScript((k) => {
        sessionStorage.setItem('yukta.apiKey', k);
      }, key);
    }
    await use(page);
  },
  api: async ({ request }, use) => {
    const api = new RealApi(request);
    await use(api);
    await api.cleanup();
  },
});

export { expect } from '@playwright/test';
