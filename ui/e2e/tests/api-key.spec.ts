// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { test, expect } from '../fixtures';
import { envelope } from '../api-envelope';

const KEY = 'test-secret-key';

const unauthorized = {
  status: 401,
  contentType: 'application/json',
  body: '{"message":"unauthorized"}',
};

test.describe('The API key gate', () => {
  test.beforeEach(async ({ page }) => {
    // The server answers 401 until a request carries the right Bearer key.
    await page.route('**/api/me', (route) =>
      route.request().headers()['authorization'] === `Bearer ${KEY}`
        ? route.fulfill(
            envelope('/api/me', {
              authenticated: true,
              keyName: 'dev',
              role: 'ADMIN',
              sessions: null,
              authEnabled: true,
              permissions: {},
            })
          )
        : route.fulfill(unauthorized)
    );
    await page.route('**/api/sessions/summaries*', (route) =>
      route.request().headers()['authorization'] === `Bearer ${KEY}`
        ? route.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify(envelope('/api/sessions/summaries', [])),
          })
        : route.fulfill(unauthorized)
    );
  });

  test('without a key only the gate shows and no page asks the server', async ({
    page,
  }) => {
    const pageCalls: string[] = [];
    page.on('request', (request) => {
      const { pathname } = new URL(request.url());
      if (pathname.startsWith('/api/') && pathname !== '/api/me') {
        pageCalls.push(request.url());
      }
    });
    await page.goto('/sessions');

    await expect(
      page.getByRole('dialog', { name: 'Enter your API key' })
    ).toBeVisible();
    await expect(page.getByRole('navigation')).toHaveCount(0);
    await expect(page.getByRole('main')).toHaveCount(0);
    expect(pageCalls).toEqual([]);
  });

  test('a wrong key is refused in place, the right one is kept and the app loads', async ({
    page,
  }) => {
    await page.goto('/sessions');
    const gate = page.getByRole('dialog', { name: 'Enter your API key' });
    const submit = gate.getByRole('button', { name: 'Continue' });
    await expect(submit).toBeDisabled();

    await gate.getByLabel('API key').fill('wrong');
    await submit.click();
    await expect(gate.getByRole('alert')).toContainText(
      'That key was not accepted. Check it and try again.'
    );
    expect(
      await page.evaluate(() => sessionStorage.getItem('yukta.apiKey'))
    ).toBeNull();

    await gate.getByLabel('API key').fill(`  ${KEY}  `);
    const loaded = page.waitForRequest(
      (request) =>
        request.url().includes('/api/sessions/summaries') &&
        request.headers()['authorization'] === `Bearer ${KEY}`
    );
    await submit.click();
    await loaded;

    await expect(gate).toHaveCount(0);
    await expect(page.getByRole('main')).toBeVisible();
    expect(
      await page.evaluate(() => sessionStorage.getItem('yukta.apiKey'))
    ).toBe(KEY);
  });

  test('a locked-out address is told how long to wait', async ({ page }) => {
    await page.goto('/sessions');
    await page.route('**/api/me', (route) =>
      route.fulfill({
        status: 429,
        headers: { 'Retry-After': '60' },
        contentType: 'application/json',
        body: '{"message":"Too many failed attempts"}',
      })
    );
    const gate = page.getByRole('dialog', { name: 'Enter your API key' });
    await gate.getByLabel('API key').fill('any');
    await gate.getByRole('button', { name: 'Continue' }).click();
    await expect(gate.getByRole('alert')).toContainText(
      'Too many failed attempts from this address. Try again in 60 seconds.'
    );
  });

  test('an address locked out at load gets the gate with the wait, not a dashboard of errors', async ({
    page,
  }) => {
    await page.route('**/api/me', (route) =>
      route.fulfill({
        status: 429,
        headers: { 'Retry-After': '45' },
        contentType: 'application/json',
        body: '{"message":"Too many failed attempts"}',
      })
    );
    const pageCalls: string[] = [];
    page.on('request', (request) => {
      const { pathname } = new URL(request.url());
      if (pathname.startsWith('/api/') && pathname !== '/api/me') {
        pageCalls.push(pathname);
      }
    });
    await page.goto('/control');

    const gate = page.getByRole('dialog', { name: 'Enter your API key' });
    await expect(gate.getByRole('alert')).toContainText(
      'Too many failed attempts from this address. Try again in 45 seconds.'
    );
    await expect(page.getByRole('main')).toHaveCount(0);
    expect(pageCalls).toEqual([]);
  });
});
