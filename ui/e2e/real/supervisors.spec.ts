// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { SupervisorEditorPage } from '../pages/SupervisorEditorPage';
import { SupervisorsPage } from '../pages/SupervisorsPage';
import { sessionJson, shell, unique } from './api';
import { expect, test } from './fixtures';

test('a supervisor is created, edited and assigned', async ({
  page,
  api,
  request,
}) => {
  const id = unique('sup');
  const editor = new SupervisorEditorPage(page);
  await editor.gotoNew();
  await editor.name.fill(id);
  await editor.decisionMaker.selectOption({ value: 'rules' });
  await editor.description.fill('made by e2e');
  await editor.settings.fill('{"rules":[]}');
  await editor.button('Save').click();
  await expect
    .poll(async () => (await request.get(`/api/supervisors/${id}`)).status())
    .toBe(200);
  api.trackSupervisor(id);

  await editor.gotoEdit(id);
  await editor.description.fill('edited by e2e');
  await editor.button('Save').click();
  await expect
    .poll(async () =>
      JSON.stringify(await (await request.get(`/api/supervisors/${id}`)).json())
    )
    .toContain('edited by e2e');

  const session = await api.applySession(
    sessionJson(unique('supsess'), 'd', shell('p', 'echo hi'))
  );
  await api.assign(id, session);
  const list = new SupervisorsPage(page);
  await list.goto(id);
  await expect(page.getByText(session).first()).toBeVisible();
});
