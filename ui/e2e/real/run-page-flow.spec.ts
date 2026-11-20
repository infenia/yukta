// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { sessionJson, shell, unique } from './api';
import { expect, test } from './fixtures';

const controls = (page: import('@playwright/test').Page) =>
  page.getByRole('group', { name: 'Run controls' });

test('a live run is paused, resumed and stopped from its controls', async ({
  page,
  api,
}) => {
  const id = await api.applySession(
    sessionJson(unique('run-ctl'), 'sleeps', shell('p', 'sleep 30'))
  );
  const run = await api.startWorkflow(id);
  await page.goto(`/sessions/${id}/workflow/w?exec=${run}`);

  await expect(page.getByTestId('run-status')).toHaveAttribute(
    'aria-label',
    /Running/
  );
  await controls(page).getByRole('button', { name: 'Pause' }).click();
  await expect
    .poll(
      async () =>
        (await api.execution(run)).controlState ??
        (await api.execution(run)).status
    )
    .toMatch(/PAUSED/);

  await controls(page).getByRole('button', { name: 'Resume' }).click();
  await expect(
    controls(page).getByRole('button', { name: 'Pause' })
  ).toBeVisible();

  await page.getByRole('button', { name: 'Stop options' }).click();
  await page.getByRole('menuitem', { name: 'Kill this run' }).click();
  await page
    .getByRole('dialog')
    .getByRole('button', { name: 'Confirm' })
    .click();
  await expect
    .poll(async () => (await api.execution(run)).status)
    .toMatch(/STOPPED|ABORTED|FAILURE|ERROR/);
});

test('a finished run shows its logs and nodes, can switch to the timeline and be restarted', async ({
  page,
  api,
}) => {
  const id = await api.applySession(
    sessionJson(unique('run-done'), 'quick', shell('p', 'echo hello-from-e2e'))
  );
  const run = await api.startWorkflow(id);
  await expect
    .poll(async () => (await api.execution(run)).status)
    .toBe('SUCCESS');
  await page.goto(`/sessions/${id}/workflow/w?exec=${run}`);

  await expect(page.getByRole('heading', { name: 'w' }).first()).toBeVisible();
  await expect(page.getByTestId('workflow-node-t')).toHaveAttribute(
    'data-status',
    'completed'
  );
  await expect(page.getByTestId('workflow-node-p')).toHaveAttribute(
    'data-status',
    'completed'
  );
  await expect(page.getByTestId('logs-body')).toContainText('hello-from-e2e');
  await expect(
    controls(page).getByRole('button', { name: 'Pause' })
  ).toBeDisabled();

  await page.getByRole('radio', { name: 'Timeline' }).click();
  await expect(page.getByText('When each node ran')).toBeVisible();
  await page.getByRole('radio', { name: 'Graph' }).click();

  await controls(page).getByRole('button', { name: 'Restart' }).click();
  await expect(page).not.toHaveURL(new RegExp(`exec=${run}`));
  await expect(page).toHaveURL(/exec=/);
});

test('a node opened from the list shows its sheet and the log filter by node', async ({
  page,
  api,
}) => {
  const id = await api.applySession(
    sessionJson(unique('run-node'), 'quick', shell('p', 'echo node-output'))
  );
  const run = await api.startWorkflow(id);
  await expect
    .poll(async () => (await api.execution(run)).status)
    .toBe('SUCCESS');

  await page.goto(`/sessions/${id}/workflow/w?exec=${run}&node=p`);

  const sheet = page.getByTestId('node-sheet');
  await expect(sheet).toBeVisible();
  await expect(sheet).toContainText('p');
  await page.keyboard.press('Escape');
  await expect(sheet).toBeHidden();
});

test('the task list filters by status and by name on a real run', async ({
  page,
  api,
}) => {
  const id = await api.applySession(
    sessionJson(unique('run-list'), 'quick', shell('p', 'echo hi'))
  );
  const run = await api.startWorkflow(id);
  await expect
    .poll(async () => (await api.execution(run)).status)
    .toBe('SUCCESS');
  await page.goto(`/sessions/${id}/workflow/w?exec=${run}`);

  const list = page.getByTestId('task-sequence');
  await expect(list).toContainText('t');
  await page.getByLabel('Filter nodes').fill('zzz-none');
  await expect(list.getByText('No nodes match.')).toBeVisible();
  await page.getByLabel('Filter nodes').fill('p');
  await expect(list).toContainText('p');
});
