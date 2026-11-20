// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { unique } from './api';
import { expect, test } from './fixtures';

test('a blank session is created with tags and opens its starter workflow editor', async ({
  page,
  api,
  request,
}) => {
  const id = unique('new-blank');
  api.track(id);
  await page.goto('/sessions/new');
  await page.getByRole('button', { name: 'Start blank' }).click();

  await page.getByLabel('Name').fill(id);
  await page.getByLabel('Description').fill('created by the new-session e2e');
  await page.getByLabel('Working directory').fill('/tmp');
  await page.getByLabel('Initiator').fill('e2e-new');
  const tag = page.getByLabel('Add a tag');
  await tag.fill('env: test');
  await tag.press('Enter');
  await tag.fill('urgent');
  await tag.press('Enter');

  // the scripted panel follows the form
  await expect(page.getByText(`"sessionId": "${id}"`).first()).toBeVisible();
  await page.getByRole('tab', { name: 'curl' }).click();
  await expect(page.getByText(/curl -X POST/)).toBeVisible();

  await page.getByRole('button', { name: 'Create session' }).click();
  await expect(page).toHaveURL(
    new RegExp(`/sessions/${id}/workflow/main/edit$`)
  );

  const body = (await (
    await request.get(`/api/sessions/${id}/config`)
  ).json()) as {
    data: {
      description: string;
      initiator: string;
      tags: Record<string, string>;
      workflows: Record<string, unknown>;
    };
  };
  expect(body.data.description).toBe('created by the new-session e2e');
  expect(body.data.initiator).toBe('e2e-new');
  expect(body.data.tags).toEqual({ env: 'test', urgent: '' });
  expect(Object.keys(body.data.workflows)).toEqual(['main']);
});

test('a working directory the server does not have is flagged', async ({
  page,
}) => {
  await page.goto('/sessions/new');
  await page.getByRole('button', { name: 'Start blank' }).click();

  const path = page.getByLabel('Working directory');
  await path.fill(`/no/such/${unique('dir')}`);
  await path.blur();

  await expect(page.getByText(/The server can't find/)).toBeVisible();
  await expect(path).toHaveAttribute('aria-invalid', 'true');
});

test('every bundled template is listed with a preview and its plugin types', async ({
  page,
  request,
}) => {
  const templates = (await (await request.get('/api/templates')).json()) as {
    data: { id: string; title: string }[];
  };
  expect(templates.data.length).toBeGreaterThan(0);
  await page.goto('/sessions/new');

  for (const { title } of templates.data) {
    await expect(page.getByRole('heading', { name: title })).toBeVisible();
  }
  await page.getByRole('button', { name: 'Preview' }).first().click();
  const dialog = page.getByRole('dialog');
  await expect(
    dialog.getByRole('button', { name: 'Use this template' })
  ).toBeVisible();
  await dialog.getByRole('button', { name: 'Close', exact: true }).click();
  await expect(dialog).toBeHidden();
});
