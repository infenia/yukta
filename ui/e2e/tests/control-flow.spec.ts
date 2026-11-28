// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { Page } from '@playwright/test';
import { test, expect } from '../fixtures';
import { envelope } from '../api-envelope';

const worker = (id: string, over: Record<string, unknown> = {}) => ({
  workerId: id,
  pools: ['default'],
  pluginTypes: ['LLM_CALL'],
  capacity: 4,
  usedSlots: 1,
  draining: false,
  registeredAt: '2026-07-26T14:00:00Z',
  leaseUntil: '2099-01-01T00:00:00Z',
  ...over,
});

interface Options {
  workers?: unknown[];
  workersStatus?: number;
  audit?: unknown[];
  auditStatus?: number;
  edition?: string;
  limits?: object;
  limitsStatus?: number;
}

/** The endpoints of the control page; `audits` collects the audit calls' query strings. */
async function mockControl(
  page: Page,
  opts: Options = {}
): Promise<{ audits: URL[] }> {
  const audits: URL[] = [];
  await page.route('**/api/supervisors/status', (r) =>
    r.fulfill(envelope('/status', []))
  );
  await page.route('**/api/edition', (r) =>
    r.fulfill(
      envelope('/edition', {
        edition: opts.edition ?? 'PLUS',
        license: 'x',
        features: [],
        source: null,
      })
    )
  );
  await page.route('**/api/workers', (r) =>
    opts.workersStatus
      ? r.fulfill({
          status: opts.workersStatus,
          contentType: 'application/json',
          body: '{"message":"workers are down"}',
        })
      : r.fulfill(envelope('/workers', opts.workers ?? []))
  );
  await page.route('**/api/control/audit*', (r) => {
    audits.push(new URL(r.request().url()));
    return opts.auditStatus
      ? r.fulfill({
          status: opts.auditStatus,
          contentType: 'application/json',
          body: '{"message":"audit is down"}',
        })
      : r.fulfill(envelope('/audit', opts.audit ?? []));
  });
  await page.route('**/api/scheduler/limits*', (r) =>
    opts.limitsStatus
      ? r.fulfill({
          status: opts.limitsStatus,
          contentType: 'application/json',
          body: '{"message":"scheduler is down"}',
        })
      : r.fulfill(
          envelope(
            '/limits',
            opts.limits ?? {
              maxConcurrent: 4,
              maxPerWorkflow: 2,
              running: 1,
              queued: 0,
              queueCapacity: 100,
            }
          )
        )
  );
  return { audits };
}

const tab = (page: Page, name: string) =>
  page
    .getByRole('tablist', { name: 'Dashboard sections' })
    .getByRole('tab', { name });

const entry = (n: number, over: Record<string, unknown> = {}) => ({
  time: `2026-07-26T14:2${n}:00`,
  executionId: `exec-00${n}-aaaa-bbbb`,
  command: 'PauseWorkflowCommand',
  actor: 'alice',
  applied: true,
  reason: null,
  ...over,
});

test.describe('Control - the page and its tabs', () => {
  test('has a title and three tabs, opening on Overview', async ({ page }) => {
    await mockControl(page);
    await page.goto('/control');

    await expect(
      page.getByRole('heading', { level: 1, name: 'Dashboard' })
    ).toBeVisible();
    await expect(
      page.getByText('How the server is doing, who and what is steering runs')
    ).toBeVisible();
    await expect(
      page.getByRole('tablist', { name: 'Dashboard sections' }).getByRole('tab')
    ).toHaveText(['Overview', 'Activity', 'Audit']);
    await expect(tab(page, 'Overview')).toHaveAttribute(
      'aria-selected',
      'true'
    );
    await expect(page.getByRole('heading', { name: 'Capacity' })).toBeVisible();
    await expect(
      page.getByRole('heading', { name: 'Throughput' })
    ).toBeVisible();
  });

  test('the tabs switch the panel and keep their name in the URL', async ({
    page,
  }) => {
    await mockControl(page);
    await page.goto('/control');

    await tab(page, 'Activity').click();
    await expect(page).toHaveURL(/\/control\?tab=activity$/);
    await expect(tab(page, 'Activity')).toHaveAttribute(
      'aria-selected',
      'true'
    );
    await expect(page.getByRole('heading', { name: 'Capacity' })).toHaveCount(
      0
    );

    await tab(page, 'Audit').click();
    await expect(page).toHaveURL(/tab=audit/);
    await expect(
      page.getByRole('heading', { name: 'Control audit' })
    ).toBeVisible();

    await tab(page, 'Overview').click();
    await expect(page).not.toHaveURL(/tab=/);
    await expect(page.getByRole('heading', { name: 'Capacity' })).toBeVisible();
  });

  test('a link to a tab opens it, and an execution in the URL opens the audit', async ({
    page,
  }) => {
    await mockControl(page);
    await page.goto('/control?tab=activity');
    await expect(tab(page, 'Activity')).toHaveAttribute(
      'aria-selected',
      'true'
    );

    await page.goto('/control?execution=exec-001-aaaa-bbbb');
    await expect(tab(page, 'Audit')).toHaveAttribute('aria-selected', 'true');
  });

  test('the tab panel is labelled by its tab', async ({ page }) => {
    await mockControl(page);
    await page.goto('/control?tab=audit');

    await expect(page.getByRole('tabpanel', { name: 'Audit' })).toBeVisible();
  });
});

test.describe('Control - workers', () => {
  test('shows a draining worker, and the slots in use as a bar', async ({
    page,
  }) => {
    await mockControl(page, {
      workers: [worker('w-1', { draining: true, usedSlots: 3, capacity: 4 })],
    });
    await page.goto('/control');

    const row = page.getByRole('row', { name: /w-1/ });
    await expect(row).toContainText('draining');
    await expect(row).toContainText('3/4');
    await expect(
      row.getByRole('progressbar', { name: 'w-1 slots in use' })
    ).toHaveAttribute('value', '3');
  });

  test('lists every column, and more than three pools or plugin types collapse into +n', async ({
    page,
  }) => {
    await mockControl(page, {
      workers: [
        worker('w-2', {
          pluginTypes: ['A', 'B', 'C', 'D', 'E'],
          pools: ['gpu', 'cpu'],
        }),
      ],
    });
    await page.goto('/control');

    for (const header of [
      'Worker id',
      'Pools',
      'Plugin types',
      'Capacity',
      'In use',
      'Lease',
    ]) {
      await expect(
        page.getByRole('columnheader', { name: header })
      ).toBeVisible();
    }
    const row = page.getByRole('row', { name: /w-2/ });
    await expect(row).toContainText('+2');
    await expect(row).toContainText('gpu');
    await expect(row).toContainText('cpu');
  });

  test('without workers the System card says so in one line, and names the Plus edition on Community', async ({
    page,
  }) => {
    await mockControl(page, { edition: 'COMMUNITY' });
    await page.goto('/control');

    await expect(
      page.getByText(
        /No workers connected \(remote workers need the Plus edition\)/
      )
    ).toBeVisible();
    // the detail table is for when there are workers
    await expect(page.getByRole('columnheader', { name: 'Pools' })).toHaveCount(
      0
    );
  });

  test('on Plus the System card just says there are none', async ({ page }) => {
    await mockControl(page);
    await page.goto('/control');

    await expect(
      page.getByText('No workers connected', { exact: true })
    ).toBeVisible();
  });

  test('a failure shows why, and Retry loads them', async ({ page }) => {
    await mockControl(page, { workersStatus: 500 });
    await page.goto('/control');
    await expect(page.getByText('Failed to load workers')).toBeVisible({
      timeout: 15_000,
    });

    await page.route('**/api/workers', (r) =>
      r.fulfill(envelope('/workers', [worker('w-3')]))
    );
    await page.getByRole('button', { name: 'Retry' }).first().click();

    await expect(page.getByRole('row', { name: /w-3/ })).toBeVisible();
  });
});

test.describe('Control - execution limits', () => {
  test('shows the limits, what runs and what waits', async ({ page }) => {
    await mockControl(page, {
      limits: {
        maxConcurrent: 4,
        maxPerWorkflow: 2,
        running: 3,
        queued: 5,
        queueCapacity: 100,
      },
    });
    await page.goto('/control');

    const section = page.getByRole('region', { name: 'Capacity' });
    await expect(section).toContainText('3 of 4');
    await expect(section).toContainText('5 of 100');
    await expect(section).toContainText('at most 2');
  });

  test('the edit dialog refuses anything but whole numbers, and Cancel changes nothing', async ({
    page,
  }) => {
    await mockControl(page);
    let posted = false;
    await page.route('**/api/scheduler/limits*', (r) => {
      if (r.request().method() === 'POST') posted = true;
      return r.fallback();
    });
    await page.goto('/config?tab=capacity');
    await page.getByRole('button', { name: 'Edit limits' }).click();
    const dialog = page.getByRole('dialog', { name: 'Edit execution limits' });
    await expect(dialog.getByLabel(/Max concurrent/)).toHaveValue('4');
    await expect(dialog.getByLabel(/Max per workflow/)).toHaveValue('2');

    await dialog.getByLabel(/Max concurrent/).fill('-1');
    await expect(
      dialog.getByText('Enter whole numbers, 0 or more.')
    ).toBeVisible();
    await expect(dialog.getByLabel(/Max concurrent/)).toHaveAttribute(
      'aria-invalid',
      'true'
    );
    await expect(dialog.getByRole('button', { name: 'Save' })).toBeDisabled();

    await dialog.getByLabel(/Max concurrent/).fill('1.5');
    await expect(dialog.getByRole('button', { name: 'Save' })).toBeDisabled();

    await dialog.getByRole('button', { name: 'Cancel' }).click();
    await expect(dialog).toBeHidden();
    expect(posted).toBe(false);
  });

  test('Save asks to confirm with the new numbers, and editing again asks again', async ({
    page,
  }) => {
    await mockControl(page);
    await page.goto('/config?tab=capacity');
    await page.getByRole('button', { name: 'Edit limits' }).click();
    const dialog = page.getByRole('dialog');

    await dialog.getByLabel(/Max concurrent/).fill('0');
    await dialog.getByRole('button', { name: 'Save' }).click();
    await expect(
      dialog.getByText(
        /Set max concurrent to Unlimited and max per workflow to 2\?/
      )
    ).toBeVisible();
    await expect(
      dialog.getByRole('button', { name: 'Confirm and save' })
    ).toBeVisible();
    await expect(
      dialog.getByText(/Lowering a limit does not stop runs/)
    ).toBeVisible();

    await dialog.getByLabel(/Max per workflow/).fill('3');
    await expect(dialog.getByRole('button', { name: 'Save' })).toBeVisible();
    await expect(
      dialog.getByRole('button', { name: 'Confirm and save' })
    ).toHaveCount(0);
  });

  test('a key without the ADMIN role is told so', async ({ page }) => {
    await mockControl(page);
    await page.route('**/api/scheduler/limits*', (r) =>
      r.request().method() === 'POST'
        ? r.fulfill({
            status: 403,
            contentType: 'application/json',
            body: '{"message":"no"}',
          })
        : r.fallback()
    );
    await page.goto('/config?tab=capacity');
    await page.getByRole('button', { name: 'Edit limits' }).click();
    const dialog = page.getByRole('dialog');
    await dialog.getByLabel(/Max concurrent/).fill('9');
    await dialog.getByRole('button', { name: 'Save' }).click();

    await dialog.getByRole('button', { name: 'Confirm and save' }).click();

    await expect(
      dialog.getByText('Changing limits needs an ADMIN key.')
    ).toBeVisible();
  });

  test('a failure loading the limits shows why', async ({ page }) => {
    await mockControl(page, { limitsStatus: 500 });
    await page.goto('/control');

    await expect(
      page.getByRole('region', { name: 'Capacity' }).getByText('Could not load')
    ).toBeVisible({ timeout: 15_000 });
  });
});

test.describe('Control - the audit', () => {
  test('lists every command with who sent it and how it ended', async ({
    page,
  }) => {
    await mockControl(page, {
      audit: [
        entry(1),
        entry(2, { actor: null, command: 'StopWorkflowCommand' }),
        entry(3, { applied: false, reason: 'not owner' }),
      ],
    });
    await page.goto('/control?tab=audit');

    for (const header of [
      'Time',
      'Actor',
      'Command',
      'Workflow',
      'Result',
      'Run',
    ]) {
      await expect(
        page.getByRole('columnheader', { name: header })
      ).toBeVisible();
    }
    const rows = page.locator('tbody tr');
    await expect(rows).toHaveCount(3);
    await expect(rows.nth(0)).toContainText('alice');
    await expect(rows.nth(0)).toContainText('PauseWorkflowCommand');
    await expect(rows.nth(0)).toContainText('exec-001-aaaa-bbbb');
    await expect(rows.nth(0)).toContainText('Applied');
    await expect(rows.nth(1)).toContainText('api');
    await expect(rows.nth(2)).toContainText('Refused');
    await expect(rows.nth(2)).toContainText('not owner');
  });

  test('says when no command was recorded, and when the audit cannot be loaded', async ({
    page,
  }) => {
    await mockControl(page);
    await page.goto('/control?tab=audit');
    await expect(
      page.getByText('No control commands recorded yet.')
    ).toBeVisible();

    await mockControl(page, { auditStatus: 500 });
    await page.reload();
    await expect(
      page.getByText('Failed to load the control audit')
    ).toBeVisible({
      timeout: 15_000,
    });
  });

  test('an execution in the URL narrows the audit to it, and Show all clears it', async ({
    page,
  }) => {
    const { audits } = await mockControl(page, { audit: [entry(1)] });
    await page.goto('/control?tab=audit&execution=exec-001-aaaa-bbbb');

    await expect(page.getByText('exec-001-aaaa-bbbb').first()).toBeVisible();
    await expect
      .poll(() => audits[audits.length - 1]?.searchParams.get('executionId'))
      .toBe('exec-001-aaaa-bbbb');

    await page.getByRole('button', { name: 'Show all' }).click();

    await expect(page).not.toHaveURL(/execution=/);
    await expect
      .poll(() => audits[audits.length - 1]?.searchParams.has('executionId'))
      .toBe(false);
  });

  test('Show more asks for 50 more entries while the list is full', async ({
    page,
  }) => {
    const { audits } = await mockControl(page, {
      audit: Array.from({ length: 50 }, (_, i) =>
        entry(i % 10, { executionId: `exec-${i}` })
      ),
    });
    await page.goto('/control?tab=audit');
    await expect(page.getByRole('button', { name: 'Show more' })).toBeVisible();
    await expect
      .poll(() => audits[audits.length - 1]?.searchParams.get('limit'))
      .toBe('50');

    await page.getByRole('button', { name: 'Show more' }).click();

    await expect
      .poll(() => audits[audits.length - 1]?.searchParams.get('limit'))
      .toBe('100');
  });

  test('a short list has no Show more', async ({ page }) => {
    await mockControl(page, { audit: [entry(1)] });
    await page.goto('/control?tab=audit');

    await expect(page.locator('tbody tr')).toHaveCount(1);
    await expect(page.getByRole('button', { name: 'Show more' })).toHaveCount(
      0
    );
  });
});

test.describe('Control - the activity', () => {
  const frame = (type: string, severity: string, event: object) =>
    `data: ${JSON.stringify({ type, severity, rootExecutionId: 'r', depth: 0, event })}\n\n`;

  const stream = (page: Page, body: string) =>
    page.route('**/api/events/stream*', (r) =>
      r.fulfill({ status: 200, contentType: 'text/event-stream', body })
    );

  const EVENTS =
    frame('NodeStatusChanged', 'INFO', {
      executionId: 'exec-1111-aaaa',
      timestamp: '2026-07-26T14:22:01',
      nodeId: 'ingest',
      status: 'RUNNING',
    }) +
    frame('NodeStatusChanged', 'ERROR', {
      executionId: 'exec-2222-bbbb',
      timestamp: '2026-07-26T14:22:02',
      nodeId: 'load',
      status: 'FAILURE',
    }) +
    frame('ControlOutcome', 'WARN', {
      executionId: 'exec-3333-cccc',
      timestamp: '2026-07-26T14:22:03',
      command: 'PauseWorkflowCommand',
      applied: false,
      reason: 'not owner',
    });

  test('says when there are no events yet', async ({ page }) => {
    await mockControl(page);
    await stream(page, ': hello\n\n');
    await page.goto('/control?tab=activity');

    await expect(page.getByText('No events yet.')).toBeVisible();
    await expect(
      page.getByText('Events appear here as workflows run.')
    ).toBeVisible();
  });

  test('lists events newest first, each saying what happened and linking to its run', async ({
    page,
  }) => {
    await mockControl(page);
    await stream(page, EVENTS);
    await page.goto('/control?tab=activity');

    const list = page.getByTestId('activity-list');
    await expect(list.locator('tbody tr')).toHaveCount(3);
    await expect(list.locator('tbody tr').first()).toContainText(
      'PauseWorkflowCommand refused: not owner'
    );
    await expect(list).toContainText('ingest');
    await expect(list).toContainText('RUNNING');
    await expect(list).toContainText('load');
    await expect(list).toContainText('FAILURE');
    await expect(list.getByRole('link', { name: 'exec-222' })).toHaveAttribute(
      'href',
      '/runs?text=exec-2222-bbbb'
    );
  });

  test('the kind buttons narrow the feed and are kept in the URL', async ({
    page,
  }) => {
    await mockControl(page);
    await stream(page, EVENTS);
    await page.goto('/control?tab=activity');
    const list = page.getByTestId('activity-list');
    await expect(list.locator('tbody tr')).toHaveCount(3);

    const kinds = page.getByRole('group', { name: 'Event kinds' });
    await kinds.getByRole('button', { name: /^Nodes/ }).click();

    await expect(page).toHaveURL(/group=control/);
    await expect(page).not.toHaveURL(/group=nodes/);
    await expect(kinds.getByRole('button', { name: /^Nodes/ })).toHaveAttribute(
      'aria-pressed',
      'false'
    );
    await expect(list.locator('tbody tr')).toHaveCount(1);

    await kinds.getByRole('button', { name: /^Nodes/ }).click();
    await expect(list.locator('tbody tr')).toHaveCount(3);
  });

  test('the severity buttons narrow the feed, and two of them show both', async ({
    page,
  }) => {
    await mockControl(page);
    await stream(page, EVENTS);
    await page.goto('/control?tab=activity');
    const list = page.getByTestId('activity-list');
    const severities = page.getByRole('group', { name: 'Severities' });

    await severities.getByRole('button', { name: 'ERROR' }).click();
    await expect(list.locator('tbody tr')).toHaveCount(1);
    await expect(list).toContainText('FAILURE');

    await severities.getByRole('button', { name: 'WARN' }).click();
    await expect(list.locator('tbody tr')).toHaveCount(2);
  });

  test('the text field narrows the feed to one run, workflow or node, trimmed', async ({
    page,
  }) => {
    await mockControl(page);
    await stream(page, EVENTS);
    await page.goto('/control?tab=activity');

    await page.getByLabel('Run, workflow or node').fill('  exec-1111  ');

    await expect(page).toHaveURL(/q=exec-1111/);
    await expect(
      page.getByTestId('activity-list').locator('tbody tr')
    ).toHaveCount(1);
    await expect(page.getByTestId('activity-list')).toContainText('ingest');
  });

  test('filters of a shared link are applied on load', async ({ page }) => {
    await mockControl(page);
    await stream(page, EVENTS);
    await page.goto('/control?tab=activity&severity=ERROR&group=nodes');

    await expect(
      page.getByTestId('activity-list').locator('tbody tr')
    ).toHaveCount(1);
    await expect(
      page
        .getByRole('group', { name: 'Severities' })
        .getByRole('button', { name: 'ERROR' })
    ).toHaveAttribute('aria-pressed', 'true');
  });

  test('Pause freezes the feed and Resume brings it back', async ({ page }) => {
    await mockControl(page);
    await stream(page, EVENTS);
    await page.goto('/control?tab=activity');
    await expect(
      page.getByTestId('activity-list').locator('tbody tr')
    ).toHaveCount(3);

    await page.getByRole('button', { name: 'Pause' }).click();
    await expect(page.getByRole('button', { name: 'Resume' })).toBeVisible();
    await expect(page.getByText(/new while paused/)).toBeVisible();

    await page.getByRole('button', { name: 'Resume' }).click();
    await expect(page.getByRole('button', { name: 'Pause' })).toBeVisible();
    await expect(page.getByText(/new while paused/)).toHaveCount(0);
  });
});
