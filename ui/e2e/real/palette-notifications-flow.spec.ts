// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { Page } from '@playwright/test';
import { choose } from '../utils';
import { ask, sessionJson, shell, unique } from './api';
import { expect, test } from './fixtures';

const palette = (page: Page) =>
  page.getByRole('dialog', { name: 'Command palette' });
const openPalette = async (page: Page) => {
  await page.getByRole('button', { name: /Search and jump to/ }).click();
  await expect(palette(page)).toBeVisible();
};

test('the palette finds a real session, its workflow and a run of it', async ({
  page,
  api,
}) => {
  const id = await api.applySession(
    sessionJson(unique('pal'), 'quick', shell('p', 'echo hi'))
  );
  const run = await api.startWorkflow(id);
  await expect
    .poll(async () => (await api.execution(run)).status)
    .toBe('SUCCESS');
  await page.goto('/sessions');
  await openPalette(page);

  await palette(page).getByRole('combobox').fill(id);
  await expect(
    palette(page)
      .getByRole('group', { name: 'Sessions' })
      .getByRole('option')
      .first()
  ).toContainText(id);
  await expect(
    palette(page)
      .getByRole('group', { name: 'Workflows' })
      .getByRole('option')
      .first()
  ).toContainText(`in ${id}`);

  await palette(page).getByRole('combobox').fill(run.slice(0, 8));
  const runOption = palette(page)
    .getByRole('group', { name: 'Runs' })
    .getByRole('option')
    .first();
  await expect(runOption).toContainText(run.slice(0, 8));
  await runOption.click();
  await expect(page).toHaveURL(
    new RegExp(`/sessions/${id}/workflow/w\\?exec=${run}`)
  );
});

test('on a run view the palette offers this workflow and its actions', async ({
  page,
  api,
}) => {
  const id = await api.applySession(
    sessionJson(unique('pal-ctx'), 'sleeps', shell('p', 'sleep 30'))
  );
  const run = await api.startWorkflow(id);
  await page.goto(`/sessions/${id}/workflow/w?exec=${run}`);
  await openPalette(page);

  const here = palette(page).getByRole('group', { name: 'This workflow' });
  await expect(
    here.getByRole('option', { name: /Copy CLI command to run it/ })
  ).toBeVisible();
  await expect(
    here.getByRole('option', { name: /Who owns this workflow\?/ })
  ).toBeVisible();
  await expect(
    here.getByRole('option', {
      name: new RegExp(`Stop run ${run.slice(0, 8)}`),
    })
  ).toBeVisible();
  await page.keyboard.press('Escape');
  await api.post(`/api/executions/${run}/stop?mode=KILL`);
});

test('a question waiting for a person shows on the bell and in its popover', async ({
  page,
  api,
}) => {
  const id = await api.applySession(
    sessionJson(unique('bell'), 'asks', ask('p', 'Ship it from the e2e?'))
  );
  await api.startWorkflow(id);
  await page.goto('/sessions');

  const bell = page.getByRole('button', { name: /^Notifications/ });
  await expect(bell).toHaveAccessibleName(/need action/);
  await bell.click();
  await expect(page.getByRole('dialog').first()).toContainText(
    'Ship it from the e2e?'
  );
  await expect(page.getByRole('dialog').first()).toContainText('Needs action');
});

test('notification preferences are saved by the server and read back', async ({
  page,
  request,
}) => {
  await page.goto('/inbox');
  await page.getByRole('button', { name: 'Preferences' }).click();
  const sheet = page.getByRole('dialog', { name: 'Notification preferences' });
  const level = sheet.getByRole('combobox', { name: 'run.finished' });
  const before = await level.getAttribute('data-value');
  const next = before === 'OFF' ? 'INBOX' : 'OFF';

  await choose(level, next);
  await sheet.getByRole('button', { name: 'Save' }).click();
  await expect(sheet).toBeHidden();

  try {
    await page.getByRole('button', { name: 'Preferences' }).click();
    await expect(
      page
        .getByRole('dialog', { name: 'Notification preferences' })
        .getByRole('combobox', { name: 'run.finished' })
    ).toHaveValue(next);
  } finally {
    const stored = (await (
      await request.get('/api/notifications/preferences')
    ).json()) as {
      data: { levels: Record<string, string>; mutedSessions: string[] };
    };
    const levels = { ...stored.data.levels };
    if (before === 'INBOX') delete levels['run.finished'];
    else levels['run.finished'] = before;
    await request.put('/api/notifications/preferences', {
      data: { levels, mutedSessions: stored.data.mutedSessions },
    });
  }
});
