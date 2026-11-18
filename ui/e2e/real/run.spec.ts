// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { WorkflowDetailsPage } from '../pages/WorkflowDetailsPage';
import { sessionJson, shell, unique } from './api';
import { expect, test } from './fixtures';

test('a run shows its logs live and ends in SUCCESS', async ({ page, api }) => {
  const id = await api.applySession(
    sessionJson(
      unique('run'),
      'logs',
      shell('p', 'echo first; sleep 2; echo second')
    )
  );
  const details = new WorkflowDetailsPage(page);
  await details.goto(id, 'w');
  await page
    .getByRole('group', { name: 'Run controls' })
    .getByRole('button')
    .first()
    .click();
  await page
    .getByRole('dialog')
    .getByRole('button', { name: 'Run', exact: true })
    .click();
  await page.waitForURL(/[?&]exec=/);
  const execution = new URL(page.url()).searchParams.get('exec') ?? '';

  // the lines arrive over SSE while the step is still running
  await expect(details.logs).toContainText('first');
  await expect(details.logs).toContainText('second');
  await expect
    .poll(async () => (await api.execution(execution)).status)
    .toBe('SUCCESS');
  await expect(details.node('p')).toBeVisible();
});
