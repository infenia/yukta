// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { sessionJson, shell, unique } from './api';
import { expect, test } from './fixtures';
import { choose } from '../utils';

const outline = (page: import('@playwright/test').Page) =>
  page.getByRole('complementary', { name: 'Nodes and edges' });

test('a node and an edge are added, validated and saved, and the server keeps them', async ({
  page,
  api,
  request,
}) => {
  const id = await api.applySession(
    sessionJson(unique('edit-wf'), 'before', shell('p', 'echo one'))
  );
  await page.goto(`/sessions/${id}/workflow/w/edit`);
  await expect(page.getByRole('heading', { name: 'Edit w' })).toBeVisible();
  await expect(
    outline(page).getByRole('tab', { name: /Nodes\s*3/ })
  ).toBeVisible();

  await page.getByLabel('Description', { exact: true }).fill('after');
  await choose(outline(page).getByLabel('Plugin type'), 'CONSOLE_TERMINAL');
  await outline(page).getByRole('button', { name: 'Add node' }).click();
  await expect(page.getByText('Unsaved changes')).toBeVisible();

  await outline(page).getByRole('tab', { name: /Edges/ }).click();
  await choose(outline(page).getByLabel('Edge source'), 'p');
  await choose(outline(page).getByLabel('Edge target'), 'console-terminal-1');
  await outline(page).getByRole('button', { name: 'Add edge' }).click();

  await page.getByRole('button', { name: 'Validate' }).click();
  await expect(page.getByText('Workflow is valid.')).toBeVisible();
  await page.getByRole('button', { name: 'Save version' }).click();
  await expect(page).toHaveURL(new RegExp(`/sessions/${id}/workflow/w$`));

  const body = (await (
    await request.get(`/api/sessions/${id}/config`)
  ).json()) as {
    data: {
      workflows: {
        w: {
          description: string;
          nodes: { nodeId: string }[];
          edges: { source: string; target: string }[];
        };
      };
    };
  };
  const w = body.data.workflows.w;
  expect(w.description).toBe('after');
  expect(w.nodes.map((n) => n.nodeId)).toContain('console-terminal-1');
  expect(w.edges).toContainEqual(
    expect.objectContaining({ source: 'p', target: 'console-terminal-1' })
  );
});

test('the server rejects a workflow that is not valid and nothing is saved', async ({
  page,
  api,
  request,
}) => {
  const id = await api.applySession(
    sessionJson(unique('edit-bad'), 'valid', shell('p', 'echo one'))
  );
  await page.goto(`/sessions/${id}/workflow/w/edit`);

  // a node nothing connects to
  await choose(outline(page).getByLabel('Plugin type'), 'PROCESS_EXECUTOR');
  await outline(page).getByRole('button', { name: 'Add node' }).click();
  await page.getByRole('button', { name: 'Save version' }).click();

  await expect(page.getByRole('alert')).toBeVisible();
  await expect(page).toHaveURL(/\/edit$/);
  const body = (await (
    await request.get(`/api/sessions/${id}/config`)
  ).json()) as {
    data: { workflows: { w: { nodes: unknown[] } } };
  };
  expect(body.data.workflows.w.nodes).toHaveLength(3);
});

test('discarding drops the draft, and a removed node takes its edges with it', async ({
  page,
  api,
}) => {
  const id = await api.applySession(
    sessionJson(unique('edit-undo'), 'undo', shell('p', 'echo one'))
  );
  await page.goto(`/sessions/${id}/workflow/w/edit`);

  await outline(page).getByRole('button', { name: /^p/ }).click();
  await page.getByRole('button', { name: 'Remove node' }).click();
  await expect(
    outline(page).getByRole('tab', { name: /Nodes\s*2/ })
  ).toBeVisible();
  await expect(
    outline(page).getByRole('tab', { name: /Edges\s*0/ })
  ).toBeVisible();

  await page.getByRole('button', { name: 'Discard' }).click();
  await expect(
    outline(page).getByRole('tab', { name: /Nodes\s*3/ })
  ).toBeVisible();
  await expect(
    outline(page).getByRole('tab', { name: /Edges\s*2/ })
  ).toBeVisible();
});
