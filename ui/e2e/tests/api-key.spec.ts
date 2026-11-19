// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { test, expect } from '../fixtures';
import { envelope } from '../api-envelope';

const KEY = 'test-secret-key';

test.describe('The API key prompt', () => {
  test.beforeEach(async ({ page }) => {
    // The server answers 401 until a request carries the right Bearer key.
    await page.route('**/api/sessions/summaries*', (route) =>
      route.request().headers()['authorization'] === `Bearer ${KEY}`
        ? route.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify(envelope('/api/sessions/summaries', [])),
          })
        : route.fulfill({
            status: 401,
            contentType: 'application/json',
            body: '{"message":"unauthorized"}',
          })
    );
  });

  test('a 401 opens the prompt, and the entered key is sent after the reload', async ({
    page,
  }) => {
    await page.goto('/sessions');

    const prompt = page.getByRole('alertdialog', { name: 'API key required' });
    await expect(prompt).toBeVisible();
    const submit = prompt.getByRole('button', { name: 'Use key' });
    await expect(submit).toBeDisabled();

    await prompt.getByLabel('API key').fill(`  ${KEY}  `);
    await expect(submit).toBeEnabled();

    const retried = page.waitForRequest(
      (request) =>
        request.url().includes('/api/sessions/summaries') &&
        request.headers()['authorization'] === `Bearer ${KEY}`
    );
    await submit.click();
    await retried;

    await expect(prompt).toHaveCount(0);
    expect(
      await page.evaluate(() => sessionStorage.getItem('yukta.apiKey'))
    ).toBe(KEY);
  });
});
