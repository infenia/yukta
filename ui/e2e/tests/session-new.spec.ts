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

const template = {
  id: '01-hello-world',
  title: 'Hello world',
  summary: 'Smallest possible workflow',
  pluginTypes: ['CONSTANT_SOURCE', 'CONSOLE_TERMINAL'],
};

test.describe('New session', () => {
  test("says when the server can't find the project path", async ({ page }) => {
    let asked: unknown;
    await page.route('**/api/sessions/validate/project-path', (route) => {
      asked = route.request().postDataJSON();
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          timestamp: '2026-07-26T14:23:23',
          status: 200,
          message: 'OK',
          path: '/project-path',
          data: { exists: false, directory: false },
        }),
      });
    });
    await page.route('**/api/templates', (route) =>
      route.fulfill(envelope('/templates', []))
    );
    await page.goto('/sessions/new');
    await page.getByRole('button', { name: 'Start blank' }).click();

    const path = page.getByLabel('Project path');
    await path.fill('/srv/repos/payments');
    await path.blur();

    await expect(
      page.getByText("The server can't find /srv/repos/payments.")
    ).toBeVisible();
    await expect(path).toHaveAttribute('aria-invalid', 'true');
    expect(asked).toEqual({ projectPath: '/srv/repos/payments' });
  });

  test('creates a session from a template, asking only for the project path', async ({
    page,
  }) => {
    let saved: { sessionId?: string; workflows?: Record<string, unknown> } = {};
    await page.route('**/api/templates', (route) =>
      route.fulfill(envelope('/templates', [template]))
    );
    await page.route('**/api/templates/01-hello-world', (route) =>
      route.fulfill(
        envelope('/templates/01-hello-world', {
          ...template,
          readme: '# 01 — Hello world',
          session: {
            sessionId: 'example-01',
            name: 'Hello world',
            description: 'Smallest possible workflow',
            initiator: 'examples',
            projectPath: '/tmp',
            tags: {},
            workflows: {
              hello: {
                workflowId: 'hello',
                description: 'Echo',
                nodes: [
                  { nodeId: 'start', type: 'CONSTANT_SOURCE', config: {} },
                ],
                edges: [],
              },
            },
          },
        })
      )
    );
    await page.route('**/api/sessions', (route) => {
      if (route.request().method() !== 'POST') return route.fallback();
      saved = route.request().postDataJSON();
      return route.fulfill(envelope('/sessions', null));
    });
    await page.goto('/sessions/new');

    await page.getByRole('button', { name: 'Use Hello world' }).click();
    await expect(page.getByLabel('Session ID')).toHaveValue('01-hello-world-1');
    await page.getByLabel('Project path').fill('/srv/repos/demo');
    await page.getByRole('button', { name: 'Create session' }).click();

    await expect.poll(() => saved.sessionId).toBe('01-hello-world-1');
    expect(Object.keys(saved.workflows ?? {})).toEqual(['hello']);
  });
});
