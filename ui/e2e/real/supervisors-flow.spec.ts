// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { sessionJson, shell, unique } from './api';
import { expect, test } from './fixtures';

test('a supervisor shows in the list with its detail, tabs and what it owns', async ({
  page,
  api,
}) => {
  const sup = await api.createSupervisor(unique('sup-view'));
  const session = await api.applySession(
    sessionJson(unique('sup-sess'), 'owned', shell('p', 'echo hi'))
  );
  await api.assign(sup, session);

  await page.goto(`/supervisors?supervisor=${sup}`);

  const nav = page.getByRole('navigation', { name: 'Supervisors' });
  await expect(
    nav.getByRole('button', { name: new RegExp(sup) })
  ).toHaveAttribute('aria-current', 'true');
  const article = page.getByRole('article');
  await expect(article.getByRole('heading', { name: sup })).toBeVisible();
  await expect(article).toContainText('Live');
  await expect(page.getByRole('tablist')).toHaveCount(0);
  await expect(page.getByRole('region', { name: 'Owns' })).toContainText(
    session
  );
  await expect(
    page.getByRole('link', { name: 'Edit supervisor' })
  ).toHaveAttribute('href', `/supervisors/${sup}/edit`);

  for (const label of [
    'Allowed operations',
    'Hierarchy',
    'Decision maker',
    'Decisions',
  ]) {
    await expect(page.getByRole('region', { name: label })).toBeVisible();
  }
  await expect(page.getByRole('group', { name: 'Changes' })).toBeVisible();
});

test('the filter finds a supervisor and Direct control lists a workflow nobody owns', async ({
  page,
  api,
}) => {
  const sup = await api.createSupervisor(unique('sup-find'));
  const free = await api.applySession(
    sessionJson(unique('sup-free'), 'unowned', shell('p', 'echo hi'))
  );
  await page.goto('/supervisors');

  await page.getByLabel('Filter supervisors').fill(sup);
  const nav = page.getByRole('navigation', { name: 'Supervisors' });
  await expect(nav.getByRole('button')).toHaveCount(2);
  await nav.getByRole('button', { name: new RegExp(sup) }).click();
  await expect(page).toHaveURL(new RegExp(`supervisor=${sup}`));

  await nav.getByRole('button', { name: /Direct control/ }).click();
  await expect(
    page.getByRole('article').getByRole('heading', { name: 'Direct control' })
  ).toBeVisible();
  await expect(
    page.getByRole('link', { name: `${free} / w`, exact: true })
  ).toBeVisible();
});
