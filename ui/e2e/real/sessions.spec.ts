// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { SessionDetailPage } from '../pages/SessionDetailPage';
import { expect, test } from './fixtures';
import { unique } from './api';

test('a session is created from a template, edited and deleted', async ({
  page,
  api,
  request,
}) => {
  const id = unique('sessions');
  await page.goto('/sessions/new');
  await page.getByRole('button', { name: 'Use template' }).first().click();
  await page.getByLabel('Session ID').fill(id);
  await page.getByRole('button', { name: 'Create session' }).click();
  await page.waitForURL(`**/sessions/${id}/workflow/**`);
  api.track(id);

  const edit = new SessionDetailPage(page);
  await edit.gotoEdit(id);
  await edit.field('Description').fill('edited by e2e');
  await edit.save.click();
  await expect
    .poll(async () => {
      const body = (await (
        await request.get(`/api/sessions/${id}`)
      ).json()) as {
        data: { description?: string };
      };
      return body.data.description;
    })
    .toBe('edited by e2e');

  await edit.goto(id);
  await page.getByRole('button', { name: 'Session actions' }).click();
  await page.getByRole('menuitem', { name: 'Delete' }).click();
  const dialog = page.getByRole('dialog');
  await dialog.getByLabel(`Type ${id} to confirm`).fill(id);
  await dialog.getByRole('button', { name: 'Delete', exact: true }).click();
  await expect(dialog.getByText(`Deleted ${id}.`)).toBeVisible();
  await expect
    .poll(async () => (await request.get(`/api/sessions/${id}`)).status())
    .toBe(404);
});
