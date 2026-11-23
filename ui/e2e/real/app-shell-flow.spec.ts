// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { sessionJson, shell, unique } from './api';
import { expect, test } from './fixtures';

test('the real server serves the single page app on a deep link and on an unknown path', async ({
  page,
}) => {
  await page.goto('/runs?status=SUCCESS');
  await expect(
    page.getByRole('heading', { level: 1, name: 'Runs' })
  ).toBeVisible();
  await expect(page.getByLabel('Status', { exact: true })).toContainText(
    'Succeeded'
  );

  await page.goto('/no/such/page');
  await expect(page.getByText('Not Found').first()).toBeVisible();
});

test('old addresses redirect and the breadcrumb of a real workflow leads back', async ({
  page,
  api,
}) => {
  const id = await api.applySession(
    sessionJson(unique('shell'), 'quick', shell('p', 'echo hi'))
  );

  await page.goto('/history?status=FAILURE');
  await expect(page).toHaveURL(/\/runs\?status=FAILURE$/);

  await page.goto(`/sessions/${id}/workflow/w`);
  const trail = page.getByRole('navigation', { name: /breadcrumb/i });
  await expect(trail.getByRole('link')).toHaveText(['Sessions', id]);
  await trail.getByRole('link', { name: id }).click();
  await expect(page).toHaveURL(new RegExp(`/sessions/${id}$`));
  await expect(page.getByRole('contentinfo')).toContainText(
    'Infenia Private Limited'
  );
});

test('the header shows who the key is and offers to change or forget it', async ({
  page,
}) => {
  await page.goto('/sessions');

  const account = page.getByRole('button', { name: 'Account', exact: true });
  await expect(account).toContainText('ADMIN');
  await account.click();
  await expect(
    page.getByRole('menuitem', { name: 'Change API key' })
  ).toBeVisible();
  await expect(page.getByRole('menuitem', { name: 'Sign out' })).toBeVisible();
});
