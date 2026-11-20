// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { sessionJson, shell, unique } from './api';
import { expect, test } from './fixtures';

test('the control page shows the sections and the limits of the server', async ({
  page,
}) => {
  await page.goto('/control');

  await expect(
    page.getByRole('heading', { level: 1, name: 'Control bus' })
  ).toBeVisible();
  await expect(
    page.getByRole('tablist', { name: 'Control bus sections' }).getByRole('tab')
  ).toHaveText(['Overview', 'Activity', 'Audit']);
  await expect(page.locator('#workers-heading')).toBeVisible();
  await expect(
    page.getByRole('heading', { name: 'Execution limits' })
  ).toBeVisible();
  await expect(page.getByRole('button', { name: 'Edit limits' })).toBeVisible();
});

test('a run started while the activity tab is open shows in the feed and links to it', async ({
  page,
  api,
}) => {
  const id = await api.applySession(
    sessionJson(unique('ctl-act'), 'quick', shell('p', 'echo hi'))
  );
  await page.goto('/control?tab=activity');
  await expect(
    page.getByText('No events yet.').or(page.getByTestId('activity-list'))
  ).toBeVisible();

  const run = await api.startWorkflow(id);

  const list = page.getByTestId('activity-list');
  await expect(
    list.getByRole('link', { name: run.slice(0, 8) }).first()
  ).toBeVisible();
  await expect(page.getByText('Live', { exact: true })).toBeVisible();

  await page.getByLabel('Execution id').fill(run.slice(0, 8));
  await expect(page).toHaveURL(new RegExp(`execution=${run.slice(0, 8)}`));
  await expect(list.getByRole('listitem').first()).toContainText(
    /→|input|events/
  );
});

test('a command sent to a run is recorded in the audit with its result', async ({
  page,
  api,
  request,
}) => {
  const id = await api.applySession(
    sessionJson(unique('ctl-aud'), 'sleeps', shell('p', 'sleep 20'))
  );
  const run = await api.startWorkflow(id);
  await api.post(`/api/executions/${run}/pause`);

  await page.goto(`/control?tab=audit&execution=${run}`);

  await expect(
    page.getByRole('heading', { name: 'Who did what' })
  ).toBeVisible();
  await expect(page.locator('tbody tr').first()).toContainText(/Pause/);
  await expect(page.locator('tbody tr').first()).toContainText(
    /Applied|Refused/
  );
  await page.getByRole('button', { name: 'Show all' }).click();
  await expect(page).not.toHaveURL(/execution=/);
  // clean up the sleeping run
  await request.post(`/api/executions/${run}/stop?mode=KILL`);
});
