// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { Page } from '@playwright/test';
import { test, expect } from '../fixtures';
import { envelope } from '../api-envelope';

const hoursAgo = (hours: number) =>
  new Date(Date.now() - hours * 3_600_000).toISOString();

const SESSION = {
  sessionId: 'dogfood',
  name: 'dogfood',
  description: 'Run the repo quality gates as a staged workflow.',
  initiator: 'examples',
  tags: ['example', 'ci'],
  projectPath: '/home/arun/projects/main',
  workflowIds: ['check', 'heal', 'wave'],
};

const WORKFLOWS = [
  {
    workflowId: 'check',
    description: 'The scoped, self-healing quality gate',
    nodeCount: 108,
    edgeCount: 134,
    status: 'SUCCESS',
  },
  {
    workflowId: 'heal',
    description: 'Repair a failed stage',
    nodeCount: 29,
    edgeCount: 49,
    status: 'FAILURE',
  },
  {
    workflowId: 'wave',
    description: 'Two independent steps in parallel worktrees',
    nodeCount: 11,
    edgeCount: 11,
    status: null,
  },
];

const RUNS = [
  {
    sessionId: 'dogfood',
    executionId: 'aaaaaaaa-1111-2222-3333-444444444444',
    workflowId: 'check',
    status: 'SUCCESS',
    startTime: hoursAgo(2),
    endTime: hoursAgo(1.9),
  },
  {
    sessionId: 'dogfood',
    executionId: 'bbbbbbbb-1111-2222-3333-444444444444',
    workflowId: 'heal',
    status: 'FAILURE',
    startTime: hoursAgo(5),
    endTime: hoursAgo(4.5),
  },
];

interface Seed {
  session?: typeof SESSION;
  workflows?: typeof WORKFLOWS;
  runs?: typeof RUNS;
}

/** The page's three reads; `requests` collects the run searches so a test can check their query. */
async function mockSession(
  page: Page,
  { session = SESSION, workflows = WORKFLOWS, runs = RUNS }: Seed = {}
): Promise<{ runSearches: URL[] }> {
  const runSearches: URL[] = [];
  // the editor page a click on New workflow opens
  await page.route('**/api/sessions/validate/project-path', (route) =>
    route.fulfill(envelope('/project-path', { exists: true, directory: true }))
  );
  const id = session.sessionId;
  await page.route(`**/api/sessions/${id}`, (route) =>
    route.request().method() === 'GET'
      ? route.fulfill(envelope(`/api/sessions/${id}`, session))
      : route.fallback()
  );
  await page.route(`**/api/sessions/${id}/workflows`, (route) =>
    route.fulfill(envelope(`/api/sessions/${id}/workflows`, workflows))
  );
  // the Run dialog reads the definition to build its form: one input, `branch`
  await page.route(`**/api/sessions/${id}/workflows/*`, (route) =>
    route.fulfill(
      envelope(`/api/sessions/${id}/workflows/check`, {
        workflowId: 'check',
        nodes: [
          { nodeId: 'n', type: 'MAPPER', config: { b: '${start.branch}' } },
        ],
        edges: [],
      })
    )
  );
  await page.route('**/api/executions?*', (route) => {
    runSearches.push(new URL(route.request().url()));
    return route.fulfill(envelope('/api/executions', runs));
  });
  return { runSearches };
}

const row = (page: Page, workflowId: string) =>
  page.getByTestId(`workflow-row-${workflowId}`);

test.describe('Session details - header panel', () => {
  test.beforeEach(async ({ page }) => {
    await mockSession(page);
    await page.goto('/sessions/dogfood');
  });

  test('shows the name, ID, description, tags, creator and size', async ({
    page,
  }) => {
    await expect(
      page.getByRole('heading', { level: 1, name: 'dogfood' })
    ).toBeVisible();
    await expect(page.getByText('Session ID: dogfood')).toBeVisible();
    await expect(page.getByText(SESSION.description)).toBeVisible();
    await expect(page.getByText('example', { exact: true })).toBeVisible();
    await expect(page.getByText('ci', { exact: true })).toBeVisible();
    await expect(page.getByText('examples', { exact: true })).toBeVisible();
    await expect(page.getByText('EX', { exact: true })).toBeVisible();
    await expect(page.getByText('3 · 148 nodes in all')).toBeVisible();
    await expect(page.getByText(SESSION.projectPath)).toBeVisible();
  });

  test('the breadcrumb leads back to the session list', async ({ page }) => {
    await page
      .getByRole('link', { name: 'Sessions', exact: true })
      .first()
      .click();

    await expect(page).toHaveURL(/\/sessions$/);
  });

  test('the working directory copies and confirms', async ({
    page,
    context,
  }) => {
    await context.grantPermissions(['clipboard-read', 'clipboard-write']);
    await page.getByRole('button', { name: 'Copy working directory' }).click();

    await expect(
      page.getByRole('button', { name: 'Working directory copied' })
    ).toBeVisible();
    expect(await page.evaluate(() => navigator.clipboard.readText())).toBe(
      SESSION.projectPath
    );
  });
});

test.describe('Session details - actions', () => {
  test.beforeEach(async ({ page }) => {
    await mockSession(page);
    await page.goto('/sessions/dogfood');
  });

  test('Edit opens the session editor', async ({ page }) => {
    await page.getByRole('link', { name: 'Edit', exact: true }).click();

    await expect(page).toHaveURL(/\/sessions\/dogfood\/edit$/);
  });

  test('New workflow asks for an ID, then opens the editor on it', async ({
    page,
  }) => {
    await page.getByRole('button', { name: 'New workflow' }).click();
    const dialog = page.getByRole('dialog', { name: 'New workflow' });
    await expect(dialog.getByLabel('Workflow ID')).toBeFocused();

    await dialog.getByLabel('Workflow ID').fill('  build-and-test  ');
    await dialog.getByRole('button', { name: 'Create and edit' }).click();

    await expect(page).toHaveURL(
      /\/sessions\/dogfood\/workflow\/build-and-test\/edit$/
    );
  });

  test('New workflow needs an ID and can be cancelled', async ({ page }) => {
    await page.getByRole('button', { name: 'New workflow' }).click();
    const dialog = page.getByRole('dialog', { name: 'New workflow' });

    await dialog.getByRole('button', { name: 'Create and edit' }).click();
    await expect(dialog).toBeVisible();
    await expect(page).toHaveURL(/\/sessions\/dogfood$/);

    await dialog.getByRole('button', { name: 'Cancel' }).click();
    await expect(dialog).toBeHidden();
  });

  test('Copy as lists the session and each workflow in CLI, curl and MCP form', async ({
    page,
    context,
  }) => {
    await context.grantPermissions(['clipboard-read', 'clipboard-write']);
    await page.route('**/api/sessions/dogfood/config', (route) =>
      route.fulfill(
        envelope('/api/sessions/dogfood/config', {
          sessionId: 'dogfood',
          name: 'dogfood',
          description: '',
          initiator: 'examples',
          tags: {},
          projectPath: '/tmp',
          workflows: {},
        })
      )
    );
    await page.getByRole('button', { name: 'Copy as' }).click();
    await expect(
      page.getByRole('menuitem', { name: 'Copy as CLI' })
    ).toBeVisible();
    await expect(
      page.getByRole('menuitem', { name: 'Copy as curl' })
    ).toBeVisible();
    await expect(
      page.getByRole('menuitem', { name: 'Copy as MCP' })
    ).toBeVisible();

    await page.getByRole('menuitem', { name: 'Copy as curl' }).click();
    for (const label of [
      'Create this session',
      'Run check',
      'Run heal',
      'Run wave',
    ]) {
      await expect(page.getByRole('menuitem', { name: label })).toBeVisible();
    }
    await page.getByRole('menuitem', { name: 'Run check' }).click();

    await expect(page.getByRole('status')).toHaveText('Copied');
    const copied = await page.evaluate(() => navigator.clipboard.readText());
    expect(copied).toContain('curl');
    expect(copied).toContain('check');
  });

  test('the ⋯ menu offers Delete', async ({ page }) => {
    await page.getByRole('button', { name: 'Session actions' }).click();

    await expect(
      page.getByRole('menuitem', { name: 'Delete', exact: true })
    ).toBeVisible();
  });

  test('a failed export shows why', async ({ page }) => {
    await page.route('**/api/sessions/dogfood/config', (route) =>
      route.fulfill({
        status: 500,
        contentType: 'application/json',
        body: JSON.stringify({ message: 'store is down' }),
      })
    );
    await page.getByRole('button', { name: 'Export JSON' }).click();

    await expect(page.getByRole('alert')).toContainText('Export failed');
  });
});

test.describe('Session details - workflows table', () => {
  test.beforeEach(async ({ page, mockWorkflowApi }) => {
    await mockWorkflowApi(page);
    await mockSession(page);
    await page.goto('/sessions/dogfood');
  });

  test('lists every workflow with its size and last run', async ({ page }) => {
    await expect(
      page.getByRole('heading', { level: 2, name: 'Workflows' })
    ).toBeVisible();
    await expect(page.getByText('3', { exact: true }).first()).toBeVisible();
    const table = page.getByRole('table').first();
    for (const header of ['Workflow', 'Size', 'Last run']) {
      await expect(
        table.getByRole('columnheader', { name: header, exact: true })
      ).toBeVisible();
    }
    await expect(row(page, 'check')).toContainText(
      'The scoped, self-healing quality gate'
    );
    await expect(row(page, 'check')).toContainText('108 nodes · 134 edges');
    await expect(row(page, 'check').getByLabel('Succeeded')).toBeVisible();
    await expect(row(page, 'heal').getByLabel('Failed')).toBeVisible();
    await expect(row(page, 'wave').getByLabel('Not run')).toBeVisible();
  });

  test('the filter matches the ID and the description', async ({ page }) => {
    const filter = page.getByRole('textbox', { name: 'Filter workflows' });

    await filter.fill('REPAIR');
    await expect(row(page, 'heal')).toBeVisible();
    await expect(row(page, 'check')).toBeHidden();

    await filter.fill('parallel worktrees');
    await expect(row(page, 'wave')).toBeVisible();
    await expect(page.getByTestId(/^workflow-row-/)).toHaveCount(1);
  });

  test('a filter that matches nothing says so, and clearing it brings the rows back', async ({
    page,
  }) => {
    const filter = page.getByRole('textbox', { name: 'Filter workflows' });
    await filter.fill('no-such-workflow');

    await expect(
      page.getByText('No workflows match your filter')
    ).toBeVisible();

    await filter.fill('');
    await expect(page.getByTestId(/^workflow-row-/)).toHaveCount(3);
  });

  test('clicking a row opens the workflow', async ({ page }) => {
    await row(page, 'heal').click();

    await expect(page).toHaveURL(/\/sessions\/dogfood\/workflow\/heal$/);
  });

  test('Enter on a focused row opens the workflow', async ({ page }) => {
    await row(page, 'wave').focus();
    await page.keyboard.press('Enter');

    await expect(page).toHaveURL(/\/sessions\/dogfood\/workflow\/wave$/);
  });

  test('the Run button opens its dialog without opening the row', async ({
    page,
  }) => {
    await page.getByRole('button', { name: 'Run check' }).click();

    await expect(
      page.getByRole('dialog', { name: 'Run workflow' })
    ).toBeVisible();
    await expect(page).toHaveURL(/\/sessions\/dogfood$/);
  });

  test('a session without workflows says so', async ({ page }) => {
    await mockSession(page, { workflows: [] });
    await page.goto('/sessions/dogfood');

    await expect(page.getByText('No workflows', { exact: true })).toBeVisible();
    await expect(page.getByText('0 · 0 nodes in all')).toBeVisible();
  });
});

test.describe('Session details - running a workflow', () => {
  test.beforeEach(async ({ page, mockWorkflowApi }) => {
    await mockWorkflowApi(page);
    await mockSession(page);
    await page.goto('/sessions/dogfood');
    await page.getByRole('button', { name: 'Run check' }).click();
  });

  test('starts the run with the JSON payload and opens it', async ({
    page,
  }) => {
    const started: { body: unknown; key: string | undefined }[] = [];
    await page.route('**/api/workflow/start', (route) => {
      started.push({
        body: route.request().postDataJSON(),
        key: route.request().headers()['idempotency-key'],
      });
      return route.fulfill(
        envelope('/api/workflow/start', { executionId: 'exec-new-1' })
      );
    });
    const dialog = page.getByRole('dialog', { name: 'Run workflow' });
    await dialog.getByRole('button', { name: '{} JSON' }).click();
    await dialog.getByLabel(/Trigger payload/).fill('{"branch": "main"}');
    await dialog.getByRole('button', { name: 'Run', exact: true }).click();

    await expect(page).toHaveURL(
      /\/sessions\/dogfood\/workflow\/check\?exec=exec-new-1$/
    );
    expect(started).toHaveLength(1);
    expect(started[0].body).toEqual({
      sessionId: 'dogfood',
      workflowId: 'check',
      payload: { branch: 'main' },
    });
    expect(started[0].key).toBeTruthy();
  });

  test('fills the payload from the form field', async ({ page }) => {
    let body: unknown;
    await page.route('**/api/workflow/start', (route) => {
      body = route.request().postDataJSON();
      return route.fulfill(
        envelope('/api/workflow/start', { executionId: 'exec-new-3' })
      );
    });
    const dialog = page.getByRole('dialog', { name: 'Run workflow' });
    await expect(dialog.getByText('1 not set')).toBeVisible();
    await dialog.getByLabel('branch').fill('main');
    await expect(dialog.getByText('1 not set')).toBeHidden();
    await dialog.getByRole('button', { name: 'Run', exact: true }).click();

    await expect(page).toHaveURL(/exec=exec-new-3$/);
    expect(body).toMatchObject({ payload: { branch: 'main' } });
  });

  test('an empty payload starts the run with an empty object', async ({
    page,
  }) => {
    let body: unknown;
    await page.route('**/api/workflow/start', (route) => {
      body = route.request().postDataJSON();
      return route.fulfill(
        envelope('/api/workflow/start', { executionId: 'exec-new-2' })
      );
    });
    await page
      .getByRole('dialog', { name: 'Run workflow' })
      .getByRole('button', { name: 'Run', exact: true })
      .click();

    await expect(page).toHaveURL(/exec=exec-new-2$/);
    expect(body).toMatchObject({ payload: {} });
  });

  test('a payload that is not JSON is refused before any request', async ({
    page,
  }) => {
    let called = false;
    await page.route('**/api/workflow/start', (route) => {
      called = true;
      return route.fallback();
    });
    const dialog = page.getByRole('dialog', { name: 'Run workflow' });
    await dialog.getByRole('button', { name: '{} JSON' }).click();
    await dialog.getByLabel(/Trigger payload/).fill('{not json');
    await dialog.getByRole('button', { name: 'Run', exact: true }).click();

    await expect(dialog.getByText('Payload is not valid JSON')).toBeVisible();
    expect(called).toBe(false);
    await expect(page).toHaveURL(/\/sessions\/dogfood$/);
  });

  test('a server refusal is shown in the dialog', async ({ page }) => {
    await page.route('**/api/workflow/start', (route) =>
      route.fulfill({
        status: 409,
        contentType: 'application/json',
        body: JSON.stringify({ message: 'The scheduler queue is full' }),
      })
    );
    const dialog = page.getByRole('dialog', { name: 'Run workflow' });
    await dialog.getByRole('button', { name: 'Run', exact: true }).click();

    await expect(dialog).toContainText('The scheduler queue is full');
    await expect(page).toHaveURL(/\/sessions\/dogfood$/);
  });

  test('Cancel closes the dialog without starting anything', async ({
    page,
  }) => {
    let called = false;
    await page.route('**/api/workflow/start', (route) => {
      called = true;
      return route.fallback();
    });
    const dialog = page.getByRole('dialog', { name: 'Run workflow' });
    await dialog.getByRole('button', { name: 'Cancel' }).click();

    await expect(dialog).toBeHidden();
    expect(called).toBe(false);
  });
});

test.describe('Session details - recent runs', () => {
  test('lists the newest runs and links to all of them', async ({
    page,
    mockWorkflowApi,
  }) => {
    await mockWorkflowApi(page);
    const { runSearches } = await mockSession(page);
    await page.goto('/sessions/dogfood');

    await expect(
      page.getByRole('heading', { name: 'Recent runs' })
    ).toBeVisible();
    const runs = page.getByRole('table').nth(1);
    for (const header of ['Status', 'Run', 'Workflow', 'Started', 'Duration']) {
      await expect(
        runs.getByRole('columnheader', { name: header, exact: true })
      ).toBeVisible();
    }
    const first = page.getByTestId(`run-row-${RUNS[0].executionId}`);
    await expect(first).toContainText('aaaaaaaa');
    await expect(first).toContainText('check');
    await expect(first.getByLabel('Succeeded')).toBeVisible();
    await expect(
      page.getByTestId(`run-row-${RUNS[1].executionId}`).getByLabel('Failed')
    ).toBeVisible();

    const search = runSearches.find((url) => url.searchParams.has('sessionId'));
    expect(search?.searchParams.get('sessionId')).toBe('dogfood');
    expect(search?.searchParams.get('limit')).toBe('5');

    await page.getByRole('link', { name: 'All runs of dogfood' }).click();
    await expect(page).toHaveURL(/\/runs\?session=dogfood$/);
  });

  test('clicking a run opens it on its workflow', async ({
    page,
    mockWorkflowApi,
  }) => {
    await mockWorkflowApi(page);
    await mockSession(page);
    await page.goto('/sessions/dogfood');

    await page.getByTestId(`run-row-${RUNS[1].executionId}`).click();

    await expect(page).toHaveURL(
      new RegExp(
        `/sessions/dogfood/workflow/heal\\?exec=${RUNS[1].executionId}$`
      )
    );
  });

  test('the run ID link opens the run once', async ({
    page,
    mockWorkflowApi,
  }) => {
    await mockWorkflowApi(page);
    await mockSession(page);
    await page.goto('/sessions/dogfood');

    await page.getByRole('link', { name: 'aaaaaaaa' }).click();

    await expect(page).toHaveURL(
      new RegExp(`/workflow/check\\?exec=${RUNS[0].executionId}$`)
    );
    await page.goBack();
    await expect(page).toHaveURL(/\/sessions\/dogfood$/);
  });

  test('a session that never ran explains how to start one', async ({
    page,
  }) => {
    await mockSession(page, { runs: [] });
    await page.goto('/sessions/dogfood');

    await expect(page.getByText(/No runs yet\./)).toBeVisible();
  });
});

test.describe('Session details - states', () => {
  test('shows a loading state while the session loads', async ({ page }) => {
    await page.route('**/api/sessions/dogfood', async (route) => {
      await new Promise((resolve) => setTimeout(resolve, 1_000));
      return route.fulfill(envelope('/api/sessions/dogfood', SESSION));
    });
    await page.route('**/api/sessions/dogfood/workflows', (route) =>
      route.fulfill(envelope('/api/sessions/dogfood/workflows', WORKFLOWS))
    );
    await page.goto('/sessions/dogfood');

    await expect(page.getByText('Loading session details...')).toBeVisible();
    await expect(
      page.getByRole('heading', { level: 1, name: 'dogfood' })
    ).toBeVisible();
  });

  test('shows an error when the session cannot be loaded', async ({ page }) => {
    await page.route('**/api/sessions/dogfood', (route) =>
      route.fulfill({
        status: 500,
        contentType: 'application/json',
        body: JSON.stringify({ message: 'boom' }),
      })
    );
    await page.goto('/sessions/dogfood');

    await expect(
      page.getByText('Failed to load session details')
    ).toBeVisible();
  });

  test('shows an error when the workflows cannot be loaded', async ({
    page,
  }) => {
    await page.route('**/api/sessions/dogfood', (route) =>
      route.fulfill(envelope('/api/sessions/dogfood', SESSION))
    );
    await page.route('**/api/sessions/dogfood/workflows', (route) =>
      route.fulfill({
        status: 500,
        contentType: 'application/json',
        body: JSON.stringify({ message: 'boom' }),
      })
    );
    await page.goto('/sessions/dogfood');

    await expect(
      page.getByText('Failed to load session details')
    ).toBeVisible();
  });

  test('a session ID with special characters is encoded in the requests', async ({
    page,
  }) => {
    const seen: string[] = [];
    await page.route('**/api/sessions/**', (route) => {
      seen.push(new URL(route.request().url()).pathname);
      return route.fallback();
    });
    await page.goto('/sessions/my%20session');

    await expect
      .poll(() => seen.some((p) => p.includes('my%20session')))
      .toBe(true);
  });
});

test.describe('Session details - a VIEWER key', () => {
  test.beforeEach(async ({ page }) => {
    await mockSession(page);
    await page.route('**/api/me', (route) =>
      route.fulfill(
        envelope('/api/me', {
          authenticated: true,
          keyName: 'reader',
          role: 'VIEWER',
          sessions: null,
          authEnabled: true,
          permissions: {
            startRuns: false,
            controlRuns: false,
            editSessions: false,
            deleteSessions: false,
            changeLimits: false,
            sendCommands: false,
          },
        })
      )
    );
    await page.goto('/sessions/dogfood');
  });

  test('cannot start a run', async ({ page }) => {
    const run = page.getByRole('button', { name: 'Run check' });

    await expect(run).toBeDisabled();
    await expect(run).toHaveAttribute('aria-description', 'Needs OPERATOR');
  });

  test('cannot delete the session', async ({ page }) => {
    await page.getByRole('button', { name: 'Session actions' }).click();

    const item = page.getByRole('menuitem', { name: 'Delete', exact: true });
    await expect(item).toHaveAttribute('aria-disabled', 'true');
    await expect(item).toHaveAttribute('title', /^Needs /);
  });

  test('still reads everything on the page', async ({ page }) => {
    await expect(row(page, 'check')).toBeVisible();
    await expect(page.getByText('Recent runs')).toBeVisible();
  });
});
