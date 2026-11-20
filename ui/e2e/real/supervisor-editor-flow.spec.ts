// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { unique } from './api';
import { expect, test } from './fixtures';

test('a supervisor is created in dry run with a parent and opens in the list', async ({
  page,
  api,
  request,
}) => {
  const parent = await api.createSupervisor(unique('sup-parent'));
  const id = unique('sup-new');
  api.trackSupervisor(id);
  await page.goto('/supervisors/new');

  await page.getByLabel('Name', { exact: true }).fill(id);
  await page.getByLabel('Description').fill('created by the editor e2e');
  await page.getByLabel('Decision maker').selectOption('rules');
  await page.getByLabel('Reports to').fill(parent);
  await page.getByLabel('Settings (JSON)').fill('{"rules":[]}');
  await page.getByRole('button', { name: 'Save' }).click();

  await expect(page).toHaveURL(new RegExp(`/supervisors\\?supervisor=${id}$`));
  await expect(
    page.getByRole('article').getByRole('heading', { name: id })
  ).toBeVisible();
  await expect(page.getByRole('article').locator('header')).toContainText(
    'Dry run'
  );
  const body = (await (await request.get(`/api/supervisors/${id}`)).json()) as {
    data: {
      dryRun: boolean;
      parents: string[];
      description: string;
      type: string;
    };
  };
  expect(body.data).toMatchObject({
    dryRun: true,
    parents: [parent],
    description: 'created by the editor e2e',
    type: 'rules',
  });
});

test('the editor refuses a bad name and bad JSON before sending anything', async ({
  page,
  request,
}) => {
  const bad = unique('sup-bad');
  await page.goto('/supervisors/new');
  const save = page.getByRole('button', { name: 'Save' });
  await page.getByLabel('Decision maker').selectOption('rules');

  await page.getByLabel('Name', { exact: true }).fill(`${bad}!`);
  await expect(save).toBeDisabled();
  await page.getByLabel('Name', { exact: true }).fill(bad);
  await page.getByLabel('Settings (JSON)').fill('{nope');
  await expect(page.getByText(/Settings are not valid JSON/)).toBeVisible();
  await expect(save).toBeDisabled();

  expect((await request.get(`/api/supervisors/${bad}`)).status()).toBe(404);
});

test('a supervisor is changed and the server keeps the change; a cycle is refused', async ({
  page,
  api,
  request,
}) => {
  const a = await api.createSupervisor(unique('sup-a'));
  const b = await api.createSupervisor(unique('sup-b'));
  await page.goto(`/supervisors/${a}/edit`);
  await expect(page.getByLabel('Name', { exact: true })).toBeDisabled();

  await page.getByLabel('Reports to').fill(b);
  await page.getByRole('checkbox', { name: /Dry run/ }).check();
  await page.getByRole('button', { name: 'Save' }).click();
  await expect(page).toHaveURL(new RegExp(`/supervisors\\?supervisor=${a}$`));
  const stored = (await (
    await request.get(`/api/supervisors/${a}`)
  ).json()) as {
    data: { dryRun: boolean; parents: string[] };
  };
  expect(stored.data).toMatchObject({ dryRun: true, parents: [b] });

  // b reporting to a would close a cycle
  await page.goto(`/supervisors/${b}/edit`);
  await page.getByLabel('Reports to').fill(a);
  await page.getByRole('button', { name: 'Save' }).click();
  await expect(page.getByRole('alert')).toBeVisible();
  await expect(page).toHaveURL(new RegExp(`/supervisors/${b}/edit$`));
});
