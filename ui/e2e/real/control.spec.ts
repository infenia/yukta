// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { WorkflowDetailsPage } from '../pages/WorkflowDetailsPage';
import { sessionJson, shell, unique } from './api';
import { expect, test } from './fixtures';

test('a running execution is paused and resumed from its run controls', async ({
  page,
  api,
}) => {
  const id = await api.applySession(
    sessionJson(unique('control'), 'sleeps', shell('s', 'sleep 60'))
  );
  const execution = await api.startWorkflow(id);
  const details = new WorkflowDetailsPage(page);
  await details.gotoRun(id, 'w', execution);
  await details.runControl('Pause').click();
  await expect
    .poll(async () => (await api.execution(execution)).controlState)
    .toBe('PAUSED');
  await details.runControl('Resume').click();
  await expect
    .poll(async () => (await api.execution(execution)).controlState)
    .not.toBe('PAUSED');
});
