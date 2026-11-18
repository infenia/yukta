// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { InboxPage } from '../pages/InboxPage';
import { ask, sessionJson, unique } from './api';
import { expect, test } from './fixtures';

test('an input is answered from the inbox and the run finishes', async ({
  page,
  api,
}) => {
  const id = await api.applySession(
    sessionJson(unique('inbox'), 'asks', ask('a', 'ship it?'))
  );
  const execution = await api.startWorkflow(id);
  const inbox = new InboxPage(page);
  await inbox.goto();
  // a toast repeats the question outside <main>; answer from the list
  const list = page.getByRole('main');
  await expect(list.getByText('ship it?')).toBeVisible();
  await list.getByRole('button', { name: 'approve', exact: true }).click();
  await expect
    .poll(async () => (await api.execution(execution)).status)
    .toBe('SUCCESS');
});
