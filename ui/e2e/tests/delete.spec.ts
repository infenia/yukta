// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { test, expect } from '../fixtures';

const WORKFLOW_URL = '/sessions/SESSION_A92_XP_2024/workflow/wf-982-xk-11';

test.describe('Deleting', () => {
  test('deletes a session from the list after typing its id', async ({
    page,
    mockSessionsApi,
  }) => {
    await mockSessionsApi(page, {});
    const deleted: string[] = [];
    await page.route('**/api/sessions/session-1', (route) => {
      if (route.request().method() !== 'DELETE') return route.fallback();
      deleted.push(route.request().url());
      return route.fulfill({ status: 204 });
    });
    await page.goto('/sessions');

    await page
      .getByRole('button', { name: /^Delete session / })
      .first()
      .click();
    const dialog = page.getByRole('dialog', {
      name: 'Delete session session-1',
    });
    await expect(dialog.getByRole('button', { name: 'Delete' })).toBeDisabled();
    await dialog.getByLabel('Type session-1 to confirm').fill('session-1');
    await dialog.getByRole('button', { name: 'Delete' }).click();
    await expect(dialog.getByText('Deleted session-1.')).toBeVisible();
    await dialog.getByRole('button', { name: 'Done' }).click();

    expect(deleted).toHaveLength(1);
    await expect(page).toHaveURL(/\/sessions$/);
  });

  test('deletes a workflow from its page, naming the runs that block it first', async ({
    page,
    mockWorkflowApi,
  }) => {
    await mockWorkflowApi(page);
    const calls: string[] = [];
    await page.route('**/api/sessions/*/workflows/*', (route) => {
      if (route.request().method() !== 'DELETE') return route.fallback();
      const url = new URL(route.request().url());
      calls.push(url.search);
      return url.search
        ? route.fulfill({ status: 204 })
        : route.fulfill({
            status: 409,
            contentType: 'application/json',
            body: JSON.stringify({
              message:
                '1 run(s) are still active: exec-091-qp-55. Stop them first, or delete with force=true to cancel them.',
            }),
          });
    });
    await page.goto(WORKFLOW_URL);

    await page.getByRole('button', { name: 'Workflow actions' }).click();
    await page.getByRole('menuitem', { name: 'Delete' }).click();
    const dialog = page.getByRole('dialog', { name: /Delete workflow/ });
    await dialog.getByLabel(/Type .* to confirm/).fill('wf-982-xk-11');
    await dialog.getByRole('button', { name: 'Delete' }).click();
    await expect(dialog.getByText('Runs are still active')).toBeVisible();
    await expect(
      dialog.getByRole('link', { name: 'exec-091-qp-55' })
    ).toBeVisible();
    await dialog.getByRole('button', { name: 'Stop runs and delete' }).click();
    await dialog
      .getByRole('button', { name: 'Yes, stop runs and delete' })
      .click();
    await dialog.getByRole('button', { name: 'Done' }).click();

    expect(calls).toEqual(['', '?force=true']);
    await expect(page).toHaveURL(/\/sessions\/SESSION_A92_XP_2024$/);
  });
});
