// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { test, expect } from '../fixtures';

const envelope = (path: string, data: unknown) => ({
  status: 200,
  contentType: 'application/json',
  body: JSON.stringify({
    timestamp: '2026-07-26T14:23:23',
    status: 200,
    message: 'OK',
    path,
    data,
  }),
});

const session = {
  sessionId: 'imported',
  name: 'Imported',
  description: 'd',
  initiator: 'me',
  tags: {},
  projectPath: '/p',
  workflows: {
    main: {
      workflowId: 'main',
      description: 'd',
      nodes: [
        { nodeId: 'a', type: 'API_TRIGGER', config: {} },
        { nodeId: 'z', type: 'CONSOLE_TERMINAL', config: {} },
      ],
      edges: [{ source: 'a', target: 'z', sourcePort: null }],
    },
  },
};

test.describe('Session import', () => {
  test('checks, reviews and imports a new session', async ({ page }) => {
    await page.route('**/api/sessions/validate', (route) =>
      route.fulfill(
        envelope('/validate', { valid: true, message: null, outputPorts: {} })
      )
    );
    await page.route('**/api/sessions/imported/config', (route) =>
      route.fulfill({ status: 404, body: '{"message":"none"}' })
    );
    let saved: unknown;
    await page.route('**/api/sessions', (route) => {
      if (route.request().method() !== 'POST') return route.fallback();
      saved = route.request().postDataJSON();
      return route.fulfill(envelope('/sessions', {}));
    });
    await page.route('**/api/sessions/imported', (route) =>
      route.fulfill({ status: 404, body: '{"message":"none"}' })
    );
    await page.goto('/sessions/import');

    await page.getByLabel('Or paste the JSON').fill(JSON.stringify(session));
    await page.getByRole('button', { name: 'Check' }).click();
    await expect(page.getByText(/New session imported/)).toBeVisible();
    await page.getByRole('button', { name: 'Import session' }).click();

    await expect(page).toHaveURL(/\/sessions\/imported$/);
    expect(saved).toEqual(session);
  });

  test('refuses invalid JSON before saving anything', async ({ page }) => {
    await page.goto('/sessions/import');
    await page.getByLabel('Or paste the JSON').fill('{nope');
    await page.getByRole('button', { name: 'Check' }).click();

    await expect(page.getByRole('alert')).toContainText('Invalid JSON');
  });
});
