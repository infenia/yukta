// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { sessionJson, shell, unique } from './api';
import { expect, test } from './fixtures';
import { choose } from '../utils';

test('the runs page lists real runs, filters them and opens one', async ({
  page,
  api,
}) => {
  const ok = await api.applySession(
    sessionJson(unique('runs-ok'), 'quick', shell('p', 'echo done'))
  );
  const bad = await api.applySession(
    sessionJson(unique('runs-bad'), 'fails', shell('p', 'exit 1'))
  );
  const okRun = await api.startWorkflow(ok);
  const badRun = await api.startWorkflow(bad);
  await expect
    .poll(async () => (await api.execution(okRun)).status)
    .toBe('SUCCESS');
  await expect
    .poll(async () => (await api.execution(badRun)).status)
    .toMatch(/FAILURE|ERROR/);

  await page.goto(`/runs?text=${okRun.slice(0, 8)}`);
  const rows = page.locator('tbody tr');
  await expect(rows).toHaveCount(1);
  await expect(rows.first()).toContainText(ok);
  await expect(rows.first().getByLabel('Succeeded')).toBeVisible();
  await expect(rows.first().getByRole('progressbar')).toBeVisible();

  // by session, then by status
  await page.goto(`/runs?session=${bad}`);
  await expect(rows).toHaveCount(1);
  await expect(rows.first().getByLabel('Failed')).toBeVisible();
  await expect(rows.first()).toContainText('failed at p');
  await choose(page.getByLabel('Status'), 'SUCCESS');
  await expect(page.getByText('No runs found.')).toBeVisible();

  // the failed chip counts the failure of the last 24 hours and filters to it
  await page.goto('/runs');
  const failed = page.getByRole('button', { name: /failed in 24 h/ });
  await expect(failed).toBeVisible();
  await failed.click();
  await expect(page).toHaveURL(/status=FAILURE/);
  await expect(page.getByLabel('Time range')).toHaveAttribute(
    'data-value',
    '24h'
  );

  // a run opens on its workflow
  await page.goto(`/runs?text=${okRun.slice(0, 8)}`);
  await rows
    .first()
    .getByRole('link', { name: okRun.slice(0, 8) })
    .click();
  await expect(page).toHaveURL(
    new RegExp(`/sessions/${ok}/workflow/w\\?exec=${okRun}`)
  );
});

test('a run ID copied from the list matches the run', async ({
  page,
  api,
  context,
}) => {
  const id = await api.applySession(
    sessionJson(unique('runs-copy'), 'quick', shell('p', 'echo done'))
  );
  const run = await api.startWorkflow(id);
  await context.grantPermissions(['clipboard-read', 'clipboard-write']);
  await page.goto(`/runs?text=${run.slice(0, 8)}`);

  await page.getByRole('button', { name: `Copy run id ${run}` }).click();

  expect(await page.evaluate(() => navigator.clipboard.readText())).toBe(run);
});

test('a run started while the page is open appears without a reload', async ({
  page,
  api,
}) => {
  const id = await api.applySession(
    sessionJson(unique('runs-live'), 'quick', shell('p', 'echo done'))
  );
  await page.goto(`/runs?session=${id}`);
  await expect(page.getByText('No runs found.')).toBeVisible();

  const run = await api.startWorkflow(id);

  await expect(page.getByRole('link', { name: run.slice(0, 8) })).toBeVisible();
  // the stream reports itself live once it has delivered an event
  await expect(page.getByTestId('runs-live')).toHaveText(
    'Updates live · filters are kept in the URL'
  );
  await expect(
    page.locator('tbody tr').first().getByLabel('Succeeded')
  ).toBeVisible();
});
