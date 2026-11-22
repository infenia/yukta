// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { sessionJson, shell, unique } from './api';
import { expect, test } from './fixtures';

test('a session page shows its data, runs a workflow and lists the run', async ({
  page,
  api,
}) => {
  const id = await api.applySession({
    ...sessionJson(unique('details'), 'quick echo', shell('p', 'echo done')),
    description: 'seeded by the details e2e',
    initiator: 'e2e-creator',
    projectPath: '/tmp',
  });

  await page.goto(`/sessions/${id}`);

  // the header panel
  await expect(page.getByRole('heading', { level: 1, name: id })).toBeVisible();
  await expect(page.getByText(`Session ID: ${id}`)).toBeVisible();
  await expect(page.getByText('seeded by the details e2e')).toBeVisible();
  await expect(page.getByText('e2e-creator', { exact: true })).toBeVisible();
  await expect(page.getByText('1 · 3 nodes in all')).toBeVisible();

  // the workflow table and its filter
  const workflow = page.getByTestId('workflow-row-w');
  await expect(workflow).toContainText('quick echo');
  await expect(workflow).toContainText('3 nodes · 2 edges');
  await expect(workflow.getByLabel('Not run')).toBeVisible();
  await page.getByRole('textbox', { name: 'Filter workflows' }).fill('nope');
  await expect(page.getByText('No workflows match your filter')).toBeVisible();
  await page.getByRole('textbox', { name: 'Filter workflows' }).fill('echo');
  await expect(workflow).toBeVisible();

  // no runs yet
  await expect(page.getByText(/No runs yet\./)).toBeVisible();

  // run it from the table, with a payload
  await page.getByRole('button', { name: 'Run w' }).click();
  const dialog = page.getByRole('dialog', { name: 'Run workflow' });
  await dialog.getByRole('button', { name: '{} JSON' }).click();
  await dialog.getByLabel(/Trigger payload/).fill('{"from": "e2e"}');
  await dialog.getByRole('button', { name: 'Run', exact: true }).click();
  await page.waitForURL(new RegExp(`/sessions/${id}/workflow/w\\?exec=`));
  const execution = new URL(page.url()).searchParams.get('exec') ?? '';
  await expect
    .poll(async () => (await api.execution(execution)).status)
    .toBe('SUCCESS');

  // back on the session page the run is listed, finished, and the workflow shows its status
  await page.goto(`/sessions/${id}`);
  await expect(page.getByTestId(/^run-row-/)).toHaveCount(1);
  await expect(
    page
      .getByTestId(/^run-row-/)
      .first()
      .getByLabel('Succeeded')
  ).toBeVisible();
  await expect(workflow.getByLabel('Succeeded')).toBeVisible();

  // a click on the run opens it
  await page
    .getByTestId(/^run-row-/)
    .first()
    .click();
  await expect(page).toHaveURL(new RegExp(`/workflow/w\\?exec=`));
});

test('New workflow opens the editor for a fresh workflow ID', async ({
  page,
  api,
}) => {
  const id = await api.applySession(
    sessionJson(unique('details-new'), 'quick', shell('p', 'echo done'))
  );
  await page.goto(`/sessions/${id}`);

  await page.getByRole('button', { name: 'New workflow' }).click();
  await page.getByLabel('Workflow ID').fill('second');
  await page.getByRole('button', { name: 'Create and edit' }).click();

  await expect(page).toHaveURL(
    new RegExp(`/sessions/${id}/workflow/second/edit$`)
  );
});

test('Delete removes the session and returns to the list', async ({
  page,
  api,
  request,
}) => {
  const id = await api.applySession(
    sessionJson(unique('details-del'), 'quick', shell('p', 'echo done'))
  );
  await page.goto(`/sessions/${id}`);

  await page.getByRole('button', { name: 'Session actions' }).click();
  await page.getByRole('menuitem', { name: 'Delete', exact: true }).click();
  const dialog = page.getByRole('dialog');
  await dialog.getByLabel(`Type ${id} to confirm`).fill(id);
  await dialog.getByRole('button', { name: 'Delete', exact: true }).click();
  await dialog.getByRole('button', { name: 'Done' }).click();

  await expect(page).toHaveURL(/\/sessions$/);
  await expect
    .poll(async () => (await request.get(`/api/sessions/${id}`)).status())
    .toBe(404);
});

test('a session that does not exist shows an error', async ({ page }) => {
  await page.goto(`/sessions/${unique('missing')}`);

  await expect(
    page.getByText(/Failed to load session details|not found/i)
  ).toBeVisible();
});
