// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { sessionJson, shell, unique } from './api';
import { expect, test } from './fixtures';

test('every plugin of the server is listed under its category', async ({
  page,
  request,
}) => {
  const body = (await (await request.get('/api/plugins')).json()) as {
    data: { type: string; category: string }[];
  };
  expect(body.data.length).toBeGreaterThan(10);
  await page.goto('/plugins');

  const nav = page.getByRole('navigation', { name: 'Plugins' });
  for (const [category, heading] of [
    ['TRIGGER', 'Triggers'],
    ['PROCESSOR', 'Processors'],
    ['TERMINAL', 'Terminals'],
  ]) {
    const count = body.data.filter((p) => p.category === category).length;
    await expect(
      nav.getByRole('heading', { name: new RegExp(`${heading}\\s*${count}`) })
    ).toBeVisible();
  }
  await expect(nav.getByRole('button')).toHaveCount(body.data.length);
});

test('a plugin shows its configuration schema, an example node and the workflows using it', async ({
  page,
  api,
}) => {
  const id = await api.applySession(
    sessionJson(unique('plugins'), 'uses a shell', shell('p', 'echo hi'))
  );
  await page.goto('/plugins?type=PROCESS_EXECUTOR');

  await expect(
    page.getByRole('heading', { level: 2, name: 'Process Executor' })
  ).toBeVisible();
  await expect(page.getByText('Restricted')).toBeVisible();
  await expect(
    page
      .getByRole('region', { name: 'On the canvas' })
      .getByTestId('workflow-node-PROCESS_EXECUTOR')
  ).toBeVisible();
  const tabs = page.getByRole('tablist', { name: 'Plugin details' });
  await expect(
    tabs.getByRole('tab', { name: /Configuration/ })
  ).toHaveAttribute('aria-selected', 'true');
  await expect(
    page.getByText('command', { exact: true }).first()
  ).toBeVisible();
  await expect(page.getByText('required').first()).toBeVisible();

  await tabs.getByRole('tab', { name: 'Example' }).click();
  await expect(page.getByText('"type": "PROCESS_EXECUTOR"')).toBeVisible();

  await tabs.getByRole('tab', { name: /Used in/ }).click();
  await expect(page.getByRole('link', { name: `${id} / w` })).toBeVisible();
});

test('filtering finds a plugin and selecting it shows its ID in the URL', async ({
  page,
}) => {
  await page.goto('/plugins');

  await page.getByLabel('Filter plugins').fill('console_terminal');
  await page
    .getByRole('navigation', { name: 'Plugins' })
    .getByRole('button', { name: /CONSOLE_TERMINAL/ })
    .click();

  await expect(page).toHaveURL(/\/plugins\?type=CONSOLE_TERMINAL$/);
  await expect(
    page.getByRole('heading', { level: 2, name: 'Console Terminal' })
  ).toBeVisible();
  await page.getByRole('tab', { name: /Ports/ }).click();
  await expect(page.getByText(/Output ports:/)).toBeVisible();
});
