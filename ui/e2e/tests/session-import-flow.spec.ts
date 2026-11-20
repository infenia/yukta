// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { Buffer } from 'node:buffer';
import type { Page } from '@playwright/test';
import { test, expect } from '../fixtures';
import { envelope } from '../api-envelope';

const workflow = (
  nodes: string[],
  edges: [string, string][],
  description = 'd'
) => ({
  workflowId: 'main',
  description,
  nodes: nodes.map((nodeId, i) => ({
    nodeId,
    type: i === 0 ? 'API_TRIGGER' : 'CONSOLE_TERMINAL',
    config: {},
  })),
  edges: edges.map(([source, target]) => ({
    source,
    target,
    sourcePort: null,
  })),
});

const session = (main = workflow(['a', 'z'], [['a', 'z']])) => ({
  sessionId: 'imported',
  name: 'Imported',
  description: 'd',
  initiator: 'me',
  tags: {},
  projectPath: '/p',
  workflows: { main },
});

interface Options {
  /** The session already on the server; none when omitted (404). */
  existing?: ReturnType<typeof session>;
  /** The message the server's workflow check answers with when the workflow is not valid. */
  invalid?: string;
  configStatus?: number;
  saveStatus?: number;
}

/** The three calls of Check and Apply; `saves` collects the POSTed sessions. */
async function mockImport(
  page: Page,
  { existing, invalid, configStatus, saveStatus = 200 }: Options = {}
): Promise<{ saves: unknown[]; validated: unknown[] }> {
  const saves: unknown[] = [];
  const validated: unknown[] = [];
  await page.route('**/api/sessions/validate', (route) => {
    validated.push(route.request().postDataJSON());
    return route.fulfill(
      envelope('/validate', {
        valid: invalid === undefined,
        message: invalid ?? null,
        outputPorts: {},
      })
    );
  });
  await page.route('**/api/sessions/imported/config', (route) =>
    configStatus
      ? route.fulfill({
          status: configStatus,
          contentType: 'application/json',
          body: '{"message":"config store is down"}',
        })
      : existing
        ? route.fulfill(envelope('/config', existing))
        : route.fulfill({ status: 404, body: '{"message":"none"}' })
  );
  await page.route('**/api/sessions', (route) => {
    if (route.request().method() !== 'POST') return route.fallback();
    saves.push(route.request().postDataJSON());
    return saveStatus === 200
      ? route.fulfill(envelope('/sessions', {}))
      : route.fulfill({
          status: saveStatus,
          contentType: 'application/json',
          body: '{"message":"cannot save right now"}',
        });
  });
  return { saves, validated };
}

const paste = (page: Page, text: string) =>
  page.getByLabel('Or paste the JSON').fill(text);
const check = (page: Page) =>
  page.getByRole('button', { name: 'Check' }).click();

test.describe('Session import - the page', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/sessions/import');
  });

  test('explains itself and starts with nothing to check', async ({ page }) => {
    await expect(
      page.getByRole('heading', { level: 1, name: 'Import session' })
    ).toBeVisible();
    await expect(
      page.getByText('Nothing is saved until you apply it.')
    ).toBeVisible();
    await expect(
      page.getByText('Drop a .json file here, or click to browse')
    ).toBeVisible();
    await expect(
      page.getByText('The JSON is checked as you type.')
    ).toBeVisible();
    await expect(page.getByRole('button', { name: 'Check' })).toBeDisabled();
    await expect(page.getByRole('status')).toHaveCount(0);
  });

  test('shows that the JSON is valid as you type, with the session and workflow count', async ({
    page,
  }) => {
    await paste(page, JSON.stringify(session()));

    await expect(page.getByRole('status')).toContainText(
      'Valid JSON: session imported, 1 workflow(s).'
    );
    await expect(page.getByLabel('Or paste the JSON')).toHaveAttribute(
      'aria-invalid',
      'false'
    );
    await expect(page.getByRole('button', { name: 'Check' })).toBeEnabled();
  });

  test('shows where the JSON is broken as you type', async ({ page }) => {
    await paste(page, '{\n  "sessionId": "x",\n  nope\n}');

    await expect(page.getByRole('status')).toContainText('Invalid JSON');
    await expect(page.getByLabel('Or paste the JSON')).toHaveAttribute(
      'aria-invalid',
      'true'
    );
  });

  test('JSON that is not a session is refused with what it needs', async ({
    page,
  }) => {
    await paste(page, '{"name": "no id"}');

    await expect(page.getByRole('status')).toContainText(
      'This is not a session: it needs a sessionId and workflows.'
    );
    await check(page);
    await expect(page.getByRole('alert')).toContainText(
      'This cannot be imported'
    );
    await expect(page.getByRole('alert')).toContainText(
      'it needs a sessionId and workflows'
    );
  });

  test('a .json file is read into the box and its name is shown', async ({
    page,
  }) => {
    await page.getByLabel('Session file (.json)').setInputFiles({
      name: 'my-session.json',
      mimeType: 'application/json',
      buffer: Buffer.from(JSON.stringify(session())),
    });

    await expect(page.getByText('my-session.json')).toBeVisible();
    await expect(page.getByLabel('Or paste the JSON')).toHaveValue(
      JSON.stringify(session())
    );
    await expect(page.getByRole('status')).toContainText('Valid JSON');
  });

  test('typing after loading a file forgets its name', async ({ page }) => {
    await page.getByLabel('Session file (.json)').setInputFiles({
      name: 'my-session.json',
      mimeType: 'application/json',
      buffer: Buffer.from(JSON.stringify(session())),
    });
    await expect(page.getByText('my-session.json')).toBeVisible();

    await paste(page, '{}');

    await expect(page.getByText('my-session.json')).toBeHidden();
    await expect(
      page.getByText('Drop a .json file here, or click to browse')
    ).toBeVisible();
  });
});

test.describe('Session import - checking', () => {
  test('a new session is reviewed, then imported and opened', async ({
    page,
  }) => {
    const { saves, validated } = await mockImport(page);
    await page.goto('/sessions/import');

    await paste(page, JSON.stringify(session()));
    await check(page);

    const review = page.getByRole('region', { name: 'Review' });
    await expect(review).toContainText(
      'New session imported with 1 workflow(s).'
    );
    expect(validated).toHaveLength(1);
    await review.getByRole('button', { name: 'Import session' }).click();

    await expect(page).toHaveURL(/\/sessions\/imported$/);
    expect(saves).toEqual([session()]);
  });

  test('every workflow is checked by the server, and the ones it refuses are listed', async ({
    page,
  }) => {
    await mockImport(page, { invalid: 'Node z has no incoming edge' });
    await page.goto('/sessions/import');

    await paste(page, JSON.stringify(session()));
    await check(page);

    const problems = page.getByRole('alert');
    await expect(problems).toContainText('This cannot be imported');
    await expect(problems).toContainText('main: Node z has no incoming edge');
    await expect(page.getByRole('region', { name: 'Review' })).toHaveCount(0);
  });

  test('a failing check call is reported against its workflow', async ({
    page,
  }) => {
    await page.route('**/api/sessions/validate', (route) =>
      route.fulfill({
        status: 500,
        contentType: 'application/json',
        body: '{"message":"validator crashed"}',
      })
    );
    await page.goto('/sessions/import');

    await paste(page, JSON.stringify(session()));
    await check(page);

    await expect(page.getByRole('alert')).toContainText(
      'main: validator crashed'
    );
  });

  test('a server error while looking for the existing session stops the import', async ({
    page,
  }) => {
    await mockImport(page, { configStatus: 500 });
    await page.goto('/sessions/import');

    await paste(page, JSON.stringify(session()));
    await check(page);

    await expect(page.getByRole('alert')).toContainText('config store is down');
    await expect(page.getByRole('region', { name: 'Review' })).toHaveCount(0);
  });

  test('editing the JSON after a check clears the review', async ({ page }) => {
    await mockImport(page);
    await page.goto('/sessions/import');
    await paste(page, JSON.stringify(session()));
    await check(page);
    await expect(page.getByRole('region', { name: 'Review' })).toBeVisible();

    await paste(page, JSON.stringify({ ...session(), name: 'Changed' }));

    await expect(page.getByRole('region', { name: 'Review' })).toHaveCount(0);
  });

  test('a refusal when applying is shown and nothing is opened', async ({
    page,
  }) => {
    await mockImport(page, { saveStatus: 409 });
    await page.goto('/sessions/import');
    await paste(page, JSON.stringify(session()));
    await check(page);

    await page.getByRole('button', { name: 'Import session' }).click();

    await expect(page.getByRole('alert')).toContainText(
      'cannot save right now'
    );
    await expect(page).toHaveURL(/\/sessions\/import$/);
    await expect(
      page.getByRole('button', { name: 'Import session' })
    ).toBeEnabled();
  });

  test('redacted secrets in the file are called out', async ({ page }) => {
    await mockImport(page);
    await page.goto('/sessions/import');
    const redacted = session(workflow(['a', 'z'], [['a', 'z']], '******'));

    await paste(page, JSON.stringify(redacted));
    await check(page);

    await expect(page.getByRole('region', { name: 'Review' })).toContainText(
      'Secrets are redacted in this file (******)'
    );
  });
});

test.describe('Session import - replacing an existing session', () => {
  const existing = session(
    workflow(['a', 'z'], [['a', 'z']], 'old description')
  );

  test('warns that the session exists and lists what changes', async ({
    page,
  }) => {
    await mockImport(page, { existing });
    await page.goto('/sessions/import');
    const incoming = session(
      workflow(
        ['a', 'y', 'q'],
        [
          ['a', 'y'],
          ['y', 'q'],
        ],
        'new description'
      )
    );

    await paste(page, JSON.stringify(incoming));
    await check(page);

    const review = page.getByRole('region', { name: 'Review' });
    await expect(review).toContainText(
      'Session imported exists. Import replaces it'
    );
    await expect(review.getByRole('heading', { name: 'main' })).toBeVisible();
    for (const line of [
      'description changed',
      '+ node y',
      '+ node q',
      '- node z',
      '+ edge a -> y',
      '+ edge y -> q',
      '- edge a -> z',
    ]) {
      await expect(review.getByText(line, { exact: true })).toBeVisible();
    }
    await expect(
      review.getByRole('button', { name: 'Replace session' })
    ).toBeVisible();
  });

  test('says "no changes" for an identical workflow and "new workflow" for an added one', async ({
    page,
  }) => {
    await mockImport(page, { existing });
    await page.goto('/sessions/import');
    const incoming = {
      ...existing,
      workflows: {
        main: existing.workflows.main,
        extra: { ...existing.workflows.main, workflowId: 'extra' },
      },
    };

    await paste(page, JSON.stringify(incoming));
    await check(page);

    const review = page.getByRole('region', { name: 'Review' });
    await expect(review.getByText('no changes')).toBeVisible();
    await expect(review.getByText('new workflow')).toBeVisible();
  });

  test('a changed node is marked with ~', async ({ page }) => {
    await mockImport(page, { existing });
    await page.goto('/sessions/import');
    const incoming = session(
      workflow(['a', 'z'], [['a', 'z']], 'old description')
    );
    incoming.workflows.main.nodes[1].config = { level: 'debug' } as never;

    await paste(page, JSON.stringify(incoming));
    await check(page);

    await expect(page.getByText('~ node z', { exact: true })).toBeVisible();
  });

  test('Replace session saves it and opens it', async ({ page }) => {
    const { saves } = await mockImport(page, { existing });
    await page.goto('/sessions/import');
    const incoming = session(
      workflow(['a', 'z'], [['a', 'z']], 'new description')
    );
    await paste(page, JSON.stringify(incoming));
    await check(page);

    await page.getByRole('button', { name: 'Replace session' }).click();

    await expect(page).toHaveURL(/\/sessions\/imported$/);
    expect(saves).toEqual([incoming]);
  });
});
