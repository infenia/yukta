// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { test, expect, defaultProgress } from '../fixtures';

const WORKFLOW_URL = '/sessions/SESSION_A92_XP_2024/workflow/wf-982-xk-11';

test.describe('A VIEWER key', () => {
  test.beforeEach(async ({ page, mockWorkflowApi }) => {
    await mockWorkflowApi(page, {
      progress: { ...defaultProgress, status: 'RUNNING' },
    });
    await page.route('**/api/me', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          timestamp: '2026-07-26T14:23:23',
          status: 200,
          message: 'OK',
          path: '/api/me',
          data: {
            authenticated: true,
            keyName: 'reader',
            role: 'VIEWER',
            sessions: null,
            authEnabled: true,
            permissions: {
              startRuns: false,
              controlRuns: false,
              editSessions: false,
              deleteSessions: false,
              changeLimits: false,
              sendCommands: false,
            },
          },
        }),
      })
    );
  });

  test('sees who they are and which controls need a higher role', async ({
    page,
  }) => {
    await page.goto(WORKFLOW_URL);

    await expect(page.getByRole('button', { name: 'Account' })).toContainText(
      'VIEWER'
    );
    const pause = page.getByRole('button', { name: 'Pause' });
    await expect(pause).toBeDisabled();
    await expect(pause).toHaveAttribute('title', 'Needs OPERATOR');
    await expect(
      page.getByRole('button', { name: 'Run workflow' })
    ).toBeDisabled();
  });

  test('a 403 shows an error and never opens the key prompt', async ({
    page,
  }) => {
    await page.route('**/api/sessions/summaries', (route) =>
      route.fulfill({
        status: 403,
        contentType: 'application/json',
        body: '{"message":"no"}',
      })
    );
    await page.goto('/');

    await expect(page.getByRole('alertdialog')).toHaveCount(0);
  });
});
