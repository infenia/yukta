// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { Page } from '@playwright/test';
import { test, expect } from '../fixtures';
import { envelope } from '../api-envelope';

const iso = (hoursAgo: number) =>
  new Date(Date.now() - hoursAgo * 3_600_000).toISOString().slice(0, 19);

interface Run {
  sessionId: string;
  executionId: string;
  workflowId: string;
  status: string;
  startTime: string | null;
  endTime: string | null;
  initiator?: string | null;
  nodes?: { done: number; total: number; failed: string | null } | null;
}

const run = (n: number, over: Partial<Run> = {}): Run => ({
  sessionId: 'build',
  executionId: `${String(n).padStart(4, '0')}abcd-1111-2222-3333-444455556666`,
  workflowId: 'check',
  status: 'SUCCESS',
  startTime: iso(2),
  endTime: iso(1.9),
  initiator: 'yk check',
  nodes: { done: 5, total: 5, failed: null },
  ...over,
});

const SESSIONS = [
  {
    sessionId: 'build',
    name: 'Build Pipeline',
    description: '',
    initiator: 'ci',
    tags: [],
    projectPath: '/p',
    workflowCount: 2,
  },
  {
    sessionId: 'deploy',
    name: 'Deploy',
    description: '',
    initiator: 'ci',
    tags: [],
    projectPath: '/p',
    workflowCount: 1,
  },
];

interface Mock {
  /** Every GET /api/executions the page made, newest last. */
  searches: URL[];
  /** Replaces the runs the server holds, for the next searches. */
  set: (runs: Run[]) => void;
}

/** Sessions, their workflows, the counts and a run search that applies the filters like the server. */
async function mockRuns(
  page: Page,
  initial: Run[] = [],
  counts = { running: 2, queued: 1, failed: 3 }
): Promise<Mock> {
  let runs = initial;
  const searches: URL[] = [];
  await page.route('**/api/sessions/summaries*', (route) =>
    route.fulfill(envelope('/summaries', SESSIONS))
  );
  await page.route('**/api/sessions/build/workflows', (route) =>
    route.fulfill(
      envelope('/workflows', [
        {
          workflowId: 'check',
          description: '',
          nodeCount: 1,
          edgeCount: 0,
          status: null,
        },
        {
          workflowId: 'heal',
          description: '',
          nodeCount: 1,
          edgeCount: 0,
          status: null,
        },
      ])
    )
  );
  await page.route('**/api/executions/counts', (route) =>
    route.fulfill(envelope('/counts', counts))
  );
  await page.route('**/api/executions?*', (route) => {
    const url = new URL(route.request().url());
    searches.push(url);
    const q = url.searchParams;
    const found = runs
      .filter((r) => !q.get('sessionId') || r.sessionId === q.get('sessionId'))
      .filter(
        (r) => !q.get('workflowId') || r.workflowId === q.get('workflowId')
      )
      .filter((r) => !q.get('status') || r.status === q.get('status'))
      .filter(
        (r) => !q.get('text') || r.executionId.includes(q.get('text') ?? '')
      )
      .slice(0, Number(q.get('limit') ?? 100));
    return route.fulfill(envelope('/executions', found));
  });
  return { searches, set: (next) => (runs = next) };
}

const lastSearch = (m: Mock) => m.searches[m.searches.length - 1];
const rows = (page: Page) => page.locator('tbody tr');

test.describe('Runs - the table', () => {
  test('shows the heading and a row with every column', async ({ page }) => {
    await mockRuns(page, [
      run(1, {
        nodes: { done: 3, total: 5, failed: 'load' },
        status: 'FAILURE',
      }),
    ]);
    await page.goto('/runs');

    await expect(
      page.getByRole('heading', { level: 1, name: 'Runs' })
    ).toBeVisible();
    await expect(
      page.getByText('Runs of every session, newest first.')
    ).toBeVisible();
    for (const header of [
      'Status',
      'Run',
      'Session / workflow',
      'Progress',
      'Started',
      'Duration',
      'Initiator',
    ]) {
      await expect(
        page.getByRole('columnheader', { name: header })
      ).toBeVisible();
    }
    const row = rows(page).first();
    await expect(row.getByLabel('Failed')).toBeVisible();
    await expect(row.getByRole('link', { name: '0001abcd' })).toBeVisible();
    await expect(row).toContainText('Build Pipeline');
    await expect(row).toContainText('check');
    await expect(row).toContainText('3/5');
    await expect(row).toContainText('failed at load');
    await expect(row).toContainText('yk check');
    await expect(
      row.getByRole('progressbar', { name: 'Progress of run 0001abcd' })
    ).toHaveAttribute('aria-valuenow', '3');
  });

  test('a run without progress or initiator shows dashes, and an unknown session shows its ID', async ({
    page,
  }) => {
    await mockRuns(page, [
      run(1, { sessionId: 'orphan', nodes: null, initiator: null }),
    ]);
    await page.goto('/runs');

    const row = rows(page).first();
    await expect(row).toContainText('orphan');
    await expect(row.getByText('—')).toHaveCount(2);
    await expect(row.getByRole('progressbar')).toHaveCount(0);
  });

  test('the footer counts the runs', async ({ page }) => {
    const mock = await mockRuns(page, [run(1)]);
    await page.goto('/runs');
    await expect(page.getByText('1 run', { exact: true })).toBeVisible();

    mock.set([run(1), run(2)]);
    await page.goto('/runs');
    await expect(page.getByText('2 runs', { exact: true })).toBeVisible();
  });

  test('says so when nothing matches, and when the search fails it offers a retry', async ({
    page,
  }) => {
    const mock = await mockRuns(page, []);
    await page.goto('/runs');
    await expect(page.getByText('No runs found.')).toBeVisible();
    await expect(
      page.getByText('Change the filters, or run a workflow.')
    ).toBeVisible();

    await page.route('**/api/executions?*', (route) =>
      route.fulfill({
        status: 500,
        contentType: 'application/json',
        body: '{"message":"boom"}',
      })
    );
    await page.reload();
    await expect(page.getByText('Failed to load runs')).toBeVisible();

    await page.route('**/api/executions?*', (route) =>
      route.fulfill(envelope('/executions', [run(7)]))
    );
    await page.getByRole('button', { name: 'Retry' }).click();
    await expect(page.getByRole('link', { name: '0007abcd' })).toBeVisible();
    expect(mock.searches.length).toBeGreaterThan(0);
  });

  test('shows a loading state while the runs load', async ({ page }) => {
    await mockRuns(page, [run(1)]);
    await page.route('**/api/executions?*', async (route) => {
      await new Promise((resolve) => setTimeout(resolve, 800));
      return route.fulfill(envelope('/executions', [run(1)]));
    });
    await page.goto('/runs');

    await expect(page.getByText('Loading…')).toBeVisible();
    await expect(page.getByRole('link', { name: '0001abcd' })).toBeVisible();
  });
});

test.describe('Runs - opening and copying', () => {
  test.beforeEach(async ({ page, mockWorkflowApi }) => {
    await mockWorkflowApi(page);
    await mockRuns(page, [run(1), run(2, { workflowId: 'heal' })]);
    await page.goto('/runs');
  });

  test('the run link opens the run on its workflow', async ({ page }) => {
    await page.getByRole('link', { name: '0002abcd' }).click();

    await expect(page).toHaveURL(
      /\/sessions\/build\/workflow\/heal\?exec=0002abcd-1111-2222-3333-444455556666$/
    );
  });

  test('clicking the row opens the run', async ({ page }) => {
    await rows(page).first().getByText('Build Pipeline').click();

    await expect(page).toHaveURL(
      /\/sessions\/build\/workflow\/check\?exec=0001abcd/
    );
  });

  test('Enter on a focused row opens the run', async ({ page }) => {
    await rows(page).nth(1).focus();
    await page.keyboard.press('Enter');

    await expect(page).toHaveURL(/\/workflow\/heal\?exec=0002abcd/);
  });

  test('the copy button copies the full run ID without opening the run', async ({
    page,
    context,
  }) => {
    await context.grantPermissions(['clipboard-read', 'clipboard-write']);
    await page
      .getByRole('button', {
        name: 'Copy run id 0001abcd-1111-2222-3333-444455556666',
      })
      .click();

    expect(await page.evaluate(() => navigator.clipboard.readText())).toBe(
      '0001abcd-1111-2222-3333-444455556666'
    );
    await expect(page).toHaveURL(/\/runs$/);
  });
});

test.describe('Runs - the count chips', () => {
  test('show the counts and each one filters the list', async ({ page }) => {
    const mock = await mockRuns(page, [run(1)], {
      running: 2,
      queued: 1,
      failed: 3,
    });
    await page.goto('/runs');
    const group = page.getByRole('group', { name: 'Run counts' });
    await expect(group.getByRole('button')).toHaveText([
      '2 running',
      '1 queued',
      '3 failed in 24 h',
    ]);

    await group.getByRole('button', { name: /running/ }).click();
    await expect(page).toHaveURL(/status=RUNNING/);
    await expect(
      group.getByRole('button', { name: /running/ })
    ).toHaveAttribute('aria-pressed', 'true');
    await expect
      .poll(() => lastSearch(mock).searchParams.get('status'))
      .toBe('RUNNING');
    await expect(page.getByLabel('Status')).toHaveValue('RUNNING');

    await group.getByRole('button', { name: /queued/ }).click();
    await expect(page).toHaveURL(/status=QUEUED/);

    await group.getByRole('button', { name: /failed in 24 h/ }).click();
    await expect(page).toHaveURL(/status=FAILURE/);
    await expect(page).toHaveURL(/range=24h/);
    await expect(page.getByLabel('Time range')).toHaveValue('24h');
  });

  test('pressing an active chip clears the filter', async ({ page }) => {
    await mockRuns(page, [run(1)]);
    await page.goto('/runs?status=RUNNING');
    const chip = page.getByRole('button', { name: /running/ });
    await expect(chip).toHaveAttribute('aria-pressed', 'true');

    await chip.click();

    await expect(chip).toHaveAttribute('aria-pressed', 'false');
    await expect(page).not.toHaveURL(/status=/);
  });
});

test.describe('Runs - the filters', () => {
  test('the session filter lists sessions by name and narrows the search', async ({
    page,
  }) => {
    const mock = await mockRuns(page, [
      run(1),
      run(2, { sessionId: 'deploy' }),
    ]);
    await page.goto('/runs');
    await expect(rows(page)).toHaveCount(2);

    const session = page.getByLabel('Session');
    await expect(session.locator('option')).toHaveText([
      'All sessions',
      'Build Pipeline',
      'Deploy',
    ]);
    await session.selectOption('deploy');

    await expect(page).toHaveURL(/session=deploy/);
    await expect(rows(page)).toHaveCount(1);
    expect(lastSearch(mock).searchParams.get('sessionId')).toBe('deploy');
  });

  test('the workflow filter needs a session, lists its workflows, and resets with it', async ({
    page,
  }) => {
    const mock = await mockRuns(page, [run(1), run(2, { workflowId: 'heal' })]);
    await page.goto('/runs');
    const workflow = page.getByLabel('Workflow');
    await expect(workflow).toBeDisabled();

    await page.getByLabel('Session').selectOption('build');
    await expect(workflow).toBeEnabled();
    await expect(workflow.locator('option')).toHaveText([
      'All workflows',
      'check',
      'heal',
    ]);
    await workflow.selectOption('heal');

    await expect(page).toHaveURL(/workflow=heal/);
    await expect(rows(page)).toHaveCount(1);
    expect(lastSearch(mock).searchParams.get('workflowId')).toBe('heal');

    await page.getByLabel('Session').selectOption('deploy');
    await expect(page).not.toHaveURL(/workflow=/);
  });

  test('the status filter offers every status and narrows the search', async ({
    page,
  }) => {
    const mock = await mockRuns(page, [
      run(1),
      run(2, { status: 'FAILURE' }),
      run(3, { status: 'ERROR' }),
    ]);
    await page.goto('/runs');

    const status = page.getByLabel('Status');
    await expect(status.locator('option')).toContainText([
      'Any status',
      'Running',
      'Queued',
      'Paused',
      'Succeeded',
      'Failed',
      'Failed (error)',
      'Timed out',
      'Stopped',
      'Interrupted',
    ]);
    await status.selectOption('ERROR');

    await expect(page).toHaveURL(/status=ERROR/);
    await expect(rows(page)).toHaveCount(1);
    expect(lastSearch(mock).searchParams.get('status')).toBe('ERROR');
  });

  test('the run ID filter is trimmed and sent as text', async ({ page }) => {
    const mock = await mockRuns(page, [run(1), run(2)]);
    await page.goto('/runs');

    await page.getByLabel('Run id contains').fill('  0002  ');

    await expect(page).toHaveURL(/text=0002/);
    await expect(rows(page)).toHaveCount(1);
    expect(lastSearch(mock).searchParams.get('text')).toBe('0002');
  });

  test('a time preset sends a since bound and names the range in the footer', async ({
    page,
  }) => {
    const mock = await mockRuns(page, [run(1)]);
    await page.goto('/runs');

    await page.getByLabel('Time range').selectOption('1h');

    await expect(page).toHaveURL(/range=1h/);
    await expect(page.getByText('1 run in the last hour')).toBeVisible();
    await expect
      .poll(() => lastSearch(mock).searchParams.has('since'))
      .toBe(true);
    expect(lastSearch(mock).searchParams.has('until')).toBe(false);
  });

  test('a custom range shows Since and Until and sends what is typed', async ({
    page,
  }) => {
    const mock = await mockRuns(page, [run(1)]);
    await page.goto('/runs');
    await expect(page.getByLabel('Since')).toHaveCount(0);

    await page.getByLabel('Time range').selectOption('custom');
    await page.getByLabel('Since').fill('2026-01-01T08:00');
    await page.getByLabel('Until').fill('2026-01-02T09:30');

    await expect(page).toHaveURL(/range=custom/);
    await expect(page.getByText('1 run in this time range')).toBeVisible();
    await expect
      .poll(() => lastSearch(mock).searchParams.get('since'))
      .toBe('2026-01-01T08:00');
    await expect
      .poll(() => lastSearch(mock).searchParams.get('until'))
      .toBe('2026-01-02T09:30');
  });

  test('the filters of a shared link are applied on load', async ({ page }) => {
    const mock = await mockRuns(page, [run(1)]);
    await page.goto(
      '/runs?session=build&workflow=check&status=SUCCESS&text=0001&range=7d'
    );

    await expect(page.getByLabel('Session')).toHaveValue('build');
    await expect(page.getByLabel('Workflow')).toHaveValue('check');
    await expect(page.getByLabel('Status')).toHaveValue('SUCCESS');
    await expect(page.getByLabel('Run id contains')).toHaveValue('0001');
    await expect(page.getByLabel('Time range')).toHaveValue('7d');
    await expect.poll(() => mock.searches.length).toBeGreaterThan(0);
    const q = lastSearch(mock).searchParams;
    expect([
      q.get('sessionId'),
      q.get('workflowId'),
      q.get('status'),
      q.get('text'),
    ]).toEqual(['build', 'check', 'SUCCESS', '0001']);
  });

  test('an unknown time range in the URL is ignored', async ({ page }) => {
    await mockRuns(page, [run(1)]);
    await page.goto('/runs?range=forever');

    await expect(page.getByLabel('Time range')).toHaveValue('');
  });

  test('an old /history link lands on /runs with its query', async ({
    page,
  }) => {
    await mockRuns(page, [run(1)]);
    await page.goto('/history?status=SUCCESS&text=0001');

    await expect(page).toHaveURL(/\/runs\?status=SUCCESS&text=0001/);
  });
});

test.describe('Runs - paging', () => {
  const many = Array.from({ length: 150 }, (_, i) => run(i));

  test('Load more adds 100 runs until the list is complete', async ({
    page,
  }) => {
    const mock = await mockRuns(page, many);
    await page.goto('/runs');
    await expect(rows(page)).toHaveCount(100);
    expect(lastSearch(mock).searchParams.get('limit')).toBe('100');

    await page.getByRole('button', { name: 'Load more' }).click();

    await expect(rows(page)).toHaveCount(150);
    await expect(page.getByText('150 runs')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Load more' })).toHaveCount(
      0
    );
  });

  test('a new filter starts again from the first page', async ({ page }) => {
    const mock = await mockRuns(page, many);
    await page.goto('/runs');
    await page.getByRole('button', { name: 'Load more' }).click();
    await expect(rows(page)).toHaveCount(150);

    await page.getByLabel('Status').selectOption('SUCCESS');

    await expect
      .poll(() => lastSearch(mock).searchParams.get('limit'))
      .toBe('100');
  });

  test('a short list has no Load more', async ({ page }) => {
    await mockRuns(page, many.slice(0, 5));
    await page.goto('/runs');

    await expect(rows(page)).toHaveCount(5);
    await expect(page.getByRole('button', { name: 'Load more' })).toHaveCount(
      0
    );
  });
});

test.describe('Runs - recovering an interrupted run', () => {
  const aborted = run(9, { status: 'ABORTED' });

  test('only an interrupted run offers Recover', async ({ page }) => {
    await mockRuns(page, [run(1), aborted]);
    await page.goto('/runs');

    await expect(page.getByRole('button', { name: 'Recover' })).toHaveCount(1);
    await expect(
      rows(page)
        .filter({ hasText: '0009abcd' })
        .getByRole('button', { name: 'Recover' })
    ).toBeVisible();
  });

  test('confirming recovers it as a new run and opens that run', async ({
    page,
    mockWorkflowApi,
  }) => {
    await mockWorkflowApi(page);
    await mockRuns(page, [aborted]);
    let recovered = '';
    await page.route('**/api/executions/*/recover', (route) => {
      recovered = new URL(route.request().url()).pathname;
      return route.fulfill(
        envelope('/recover', { executionId: 'exec-recovered-1' })
      );
    });
    await page.goto('/runs');

    await page.getByRole('button', { name: 'Recover' }).click();
    const dialog = page.getByRole('dialog', { name: 'Recover this run?' });
    await expect(dialog).toContainText('Continue this run as a new run?');
    await dialog.getByRole('button', { name: 'Recover' }).click();

    await expect(page).toHaveURL(/\/workflow\/check\?exec=exec-recovered-1$/);
    expect(recovered).toBe(`/api/executions/${aborted.executionId}/recover`);
  });

  test('Cancel closes the dialog and recovers nothing', async ({ page }) => {
    await mockRuns(page, [aborted]);
    let called = false;
    await page.route('**/api/executions/*/recover', (route) => {
      called = true;
      return route.fallback();
    });
    await page.goto('/runs');
    await page.getByRole('button', { name: 'Recover' }).click();

    await page
      .getByRole('dialog', { name: 'Recover this run?' })
      .getByRole('button', { name: 'Cancel' })
      .click();

    await expect(page.getByRole('dialog')).toHaveCount(0);
    expect(called).toBe(false);
  });

  test('a refusal is shown under the button', async ({ page }) => {
    await mockRuns(page, [aborted]);
    await page.route('**/api/executions/*/recover', (route) =>
      route.fulfill({
        status: 409,
        contentType: 'application/json',
        body: '{"message":"This run cannot be recovered"}',
      })
    );
    await page.goto('/runs');
    await page.getByRole('button', { name: 'Recover' }).click();

    await page
      .getByRole('dialog', { name: 'Recover this run?' })
      .getByRole('button', { name: 'Recover' })
      .click();

    await expect(page.getByText('This run cannot be recovered')).toBeVisible();
    await expect(page).toHaveURL(/\/runs$/);
  });

  test('a VIEWER key cannot recover', async ({ page }) => {
    await mockRuns(page, [aborted]);
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
    await page.goto('/runs');

    await expect(page.getByRole('button', { name: 'Recover' })).toBeDisabled();
  });
});

test.describe('Runs - live updates and phones', () => {
  test('a finished workflow on the event stream refreshes the list', async ({
    page,
  }) => {
    const mock = await mockRuns(page, [run(1)]);
    const frame = `data: ${JSON.stringify({
      type: 'WorkflowStatusChanged',
      severity: 'INFO',
      rootExecutionId: 'r',
      depth: 0,
      event: {
        executionId: '0002abcd-1111-2222-3333-444455556666',
        timestamp: iso(0),
        status: 'SUCCESS',
      },
    })}\n\n`;
    await page.route('**/api/events/stream*', async (route) => {
      // the new run exists by the time the event arrives
      mock.set([run(1), run(2)]);
      return route.fulfill({
        status: 200,
        contentType: 'text/event-stream',
        body: frame,
      });
    });
    await page.goto('/runs');

    await expect(page.getByRole('link', { name: '0002abcd' })).toBeVisible();
  });

  test('on a phone each run is a card of labelled values', async ({ page }) => {
    await mockRuns(page, [run(1)]);
    await page.setViewportSize({ width: 390, height: 800 });
    await page.goto('/runs');

    await expect(
      page.getByRole('columnheader', { name: 'Status' })
    ).toBeHidden();
    const row = rows(page).first();
    await expect(row).toBeVisible();
    await expect(row.getByRole('link', { name: '0001abcd' })).toBeVisible();
    await expect(row.locator('td[data-label="Initiator"]')).toContainText(
      'yk check'
    );
    await expect(row.locator('td[data-label="Progress"]')).toContainText('5/5');
  });
});
