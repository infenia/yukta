// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { RunsPage } from '../pages/RunsPage';
import { sessionJson, shell, unique } from './api';
import { expect, test } from './fixtures';

test('a finished run is listed in history with its status', async ({
  page,
  api,
}) => {
  const id = await api.applySession(
    sessionJson(unique('runs'), 'quick', shell('p', 'echo done'))
  );
  const execution = await api.startWorkflow(id);
  await expect
    .poll(async () => (await api.execution(execution)).status)
    .toBe('SUCCESS');
  const runs = new RunsPage(page);
  await runs.gotoHistory('SUCCESS');
  await expect(runs.run(execution.slice(0, 8)).first()).toBeVisible();
});
