// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { sessionJson, shell, unique } from './api';
import { expect, test } from './fixtures';

test('a session is imported, then replaced with a reviewed diff', async ({
  page,
  api,
  request,
}) => {
  const id = unique('import');
  api.track(id);
  const first = sessionJson(id, 'first', shell('p', 'echo one'));
  await page.goto('/sessions/import');

  // new session
  await page.getByLabel('Or paste the JSON').fill(JSON.stringify(first));
  await expect(page.getByRole('status')).toContainText(`session ${id}`);
  await page.getByRole('button', { name: 'Check' }).click();
  await expect(page.getByRole('region', { name: 'Review' })).toContainText(
    `New session ${id} with 1 workflow(s).`
  );
  await page.getByRole('button', { name: 'Import session' }).click();
  await expect(page).toHaveURL(new RegExp(`/sessions/${id}$`));
  await expect(page.getByTestId('workflow-row-w')).toContainText('first');

  // the same ID again, with a changed workflow: the review lists what changes
  const second = sessionJson(id, 'second', shell('p', 'echo two'));
  await page.goto('/sessions/import');
  await page.getByLabel('Or paste the JSON').fill(JSON.stringify(second));
  await page.getByRole('button', { name: 'Check' }).click();
  const review = page.getByRole('region', { name: 'Review' });
  await expect(review).toContainText(`Session ${id} exists.`);
  await expect(review.getByText('description changed')).toBeVisible();
  await expect(review.getByText('~ node p', { exact: true })).toBeVisible();
  await review.getByRole('button', { name: 'Replace session' }).click();
  await expect(page).toHaveURL(new RegExp(`/sessions/${id}$`));

  const body = (await (
    await request.get(`/api/sessions/${id}/config`)
  ).json()) as {
    data: { workflows: { w: { description: string } } };
  };
  expect(body.data.workflows.w.description).toBe('second');
});

test('the server refuses a workflow with an unknown plugin type', async ({
  page,
}) => {
  const id = unique('import-bad');
  const bad = sessionJson(id, 'bad', {
    nodeId: 'p',
    type: 'NO_SUCH_PLUGIN',
    config: {},
  });
  await page.goto('/sessions/import');

  await page.getByLabel('Or paste the JSON').fill(JSON.stringify(bad));
  await page.getByRole('button', { name: 'Check' }).click();

  await expect(page.getByRole('alert')).toContainText(
    'This cannot be imported'
  );
  await expect(page.getByRole('alert')).toContainText('w:');
  await expect(page.getByRole('region', { name: 'Review' })).toHaveCount(0);
});

test('editing a session changes its name and tags and keeps its workflows', async ({
  page,
  api,
  request,
}) => {
  const id = await api.applySession({
    ...sessionJson(unique('edit'), 'kept', shell('p', 'echo keep')),
    tags: { team: 'core' },
  });
  await page.goto(`/sessions/${id}/edit`);
  await expect(page.getByRole('heading', { name: `Edit ${id}` })).toBeVisible();
  await expect(page.getByLabel('Session ID')).toHaveAttribute('readonly', '');
  await expect(page.getByRole('button', { name: 'team: core' })).toBeVisible();

  await page.getByLabel('Name').fill('Renamed by e2e');
  await page.getByRole('button', { name: 'Remove tag team' }).click();
  const add = page.getByLabel('Add a tag');
  await add.fill('env: test');
  await add.press('Enter');
  await page.getByRole('button', { name: 'Save' }).click();
  await expect(page).toHaveURL(new RegExp(`/sessions/${id}$`));

  await expect
    .poll(async () => {
      const body = (await (
        await request.get(`/api/sessions/${id}/config`)
      ).json()) as {
        data: {
          name: string;
          tags: Record<string, string>;
          workflows: Record<string, { description: string }>;
        };
      };
      return JSON.stringify([
        body.data.name,
        body.data.tags,
        body.data.workflows['w']?.description,
      ]);
    })
    .toBe(JSON.stringify(['Renamed by e2e', { env: 'test' }, 'kept']));
});

test('a session that changed while it was open is not overwritten', async ({
  page,
  api,
  request,
}) => {
  const id = await api.applySession(
    sessionJson(unique('edit-race'), 'race', shell('p', 'echo race'))
  );
  await page.goto(`/sessions/${id}/edit`);
  await expect(page.getByLabel('Name')).toHaveValue(id);

  // someone else saves in the meantime
  const current = (await (
    await request.get(`/api/sessions/${id}/config`)
  ).json()) as { data: Record<string, unknown> };
  expect(
    (
      await request.post('/api/sessions', {
        data: { ...current.data, description: 'changed elsewhere' },
      })
    ).ok()
  ).toBeTruthy();

  await page.getByLabel('Name').fill('my edit');
  await page.getByRole('button', { name: 'Save' }).click();

  await expect(
    page.getByText(/This session changed since you opened it\./)
  ).toBeVisible();
  await expect(page).toHaveURL(new RegExp(`/sessions/${id}/edit$`));
});
