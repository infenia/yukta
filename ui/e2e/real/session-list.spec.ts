// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { sessionJson, shell, unique } from './api';
import { expect, test } from './fixtures';

test('the session list shows the last run and filters by last run and creator', async ({
  page,
  api,
}) => {
  const creator = unique('creator');
  const tag = unique('tag');
  const seed = async (id: string) =>
    api.applySession({
      ...sessionJson(id, 'quick', shell('p', 'echo done')),
      initiator: creator,
      tags: { [tag]: 'on' },
    });
  const ran = await seed(unique('list-ran'));
  const idle = await seed(unique('list-idle'));
  const execution = await api.startWorkflow(ran);
  await expect
    .poll(async () => (await api.execution(execution)).status)
    .toBe('SUCCESS');

  // the tag isolates this test's sessions on a server that holds others
  await page.goto(`/sessions?tag=${encodeURIComponent(tag)}`);
  const rows = page.locator('tbody tr');
  await expect(rows).toHaveCount(2);
  const ranRow = rows.filter({ hasText: ran });
  const idleRow = rows.filter({ hasText: idle });
  await expect(ranRow).toContainText('w ·');
  await expect(ranRow.getByLabel('Succeeded')).toBeVisible();
  await expect(idleRow.getByLabel('Not run')).toBeVisible();
  await expect(ranRow).toContainText(creator);
  await expect(page.getByText('1–2 of 2 items')).toBeVisible();

  // Last run filter
  await page.getByRole('button', { name: /last run filter/i }).click();
  await page.getByRole('menuitemcheckbox', { name: 'Not run' }).click();
  await page.keyboard.press('Escape');
  await expect(rows).toHaveCount(1);
  await expect(rows.first()).toContainText(idle);
  await page.getByLabel('Reset filters').click();
  await page.goto(`/sessions?tag=${encodeURIComponent(tag)}`);

  // Creator filter
  await page.getByRole('button', { name: /creator filter/i }).click();
  await page.getByRole('menuitemcheckbox', { name: creator }).click();
  await page.keyboard.press('Escape');
  await expect(page).toHaveURL(new RegExp(`initiator=${creator}`));
  await expect(rows).toHaveCount(2);

  // System toggle: ours are not system sessions, so the toggle only changes the URL
  const system = page.getByRole('button', { name: 'Show system sessions' });
  await system.click();
  await expect(system).toHaveAttribute('aria-pressed', 'true');
  await expect(page).toHaveURL(/system=1/);

  // a row opens its session
  await ranRow.getByText(ran, { exact: true }).first().click();
  await expect(page).toHaveURL(new RegExp(`/sessions/${ran}`));
});
