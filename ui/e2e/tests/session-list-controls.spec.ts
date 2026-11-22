// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { test, expect } from '../fixtures';
import type { SessionListItem } from '../../src/features/sessions/types/session';

const hoursAgo = (hours: number) =>
  new Date(Date.now() - hours * 3_600_000).toISOString();

const session = (
  name: string,
  over: Partial<SessionListItem> = {}
): SessionListItem => ({
  sessionId: name.toLowerCase().replace(/\s+/g, '-'),
  name,
  description: `${name} description`,
  initiator: 'alice',
  tags: [],
  projectPath: `/work/${name.toLowerCase().replace(/\s+/g, '-')}`,
  workflowCount: 1,
  ...over,
});

const run = (workflowId: string, status: string, hours = 4) => ({
  executionId: `exec-${workflowId}`,
  workflowId,
  status,
  startTime: hoursAgo(hours),
  endTime: hoursAgo(hours - 0.1),
});

const rows = (page: import('@playwright/test').Page) =>
  page.locator('tbody tr');

test.describe('Session list - columns and hints', () => {
  test('shows every column, the last run and the URL hint', async ({
    page,
    mockSessionsApi,
  }) => {
    await mockSessionsApi(page, {
      sessions: [
        session('Hello world', {
          lastRun: run('hello', 'SUCCESS'),
          workflowCount: 3,
        }),
      ],
    });
    await page.goto('/sessions');

    await expect(page.getByRole('heading', { name: 'Sessions' })).toBeVisible();
    await expect(
      page.getByText('A session holds workflows and their runs.')
    ).toBeVisible();
    for (const header of [
      'Name',
      'Last run',
      'Workflows',
      'Creator',
      'Tags',
      'Working directory',
    ]) {
      await expect(
        page.getByRole('columnheader', { name: header })
      ).toBeVisible();
    }
    const row = rows(page).first();
    await expect(row).toContainText('hello · 4 hr. ago');
    await expect(row.getByLabel('Succeeded')).toBeVisible();
    await expect(page.getByText('Filters are kept in the URL')).toBeVisible();
    await expect(
      page
        .getByRole('link', { name: 'New session' })
        .or(page.getByRole('button', { name: 'New session' }))
    ).toBeVisible();
  });

  test('a session that never ran shows the not-run status', async ({
    page,
    mockSessionsApi,
  }) => {
    await mockSessionsApi(page, { sessions: [session('Never ran')] });
    await page.goto('/sessions');

    await expect(rows(page).first().getByLabel('Not run')).toBeVisible();
  });
});

test.describe('Session list - Creator and Last run filters', () => {
  const sessions = [
    session('Alpha', { initiator: 'alice', lastRun: run('a', 'FAILURE') }),
    session('Beta', { initiator: 'bob', lastRun: run('b', 'SUCCESS') }),
    session('Gamma', { initiator: 'alice' }),
  ];

  test.beforeEach(async ({ page, mockSessionsApi }) => {
    await mockSessionsApi(page, { sessions });
    await page.goto('/sessions');
    await expect(rows(page)).toHaveCount(3);
  });

  test('the Creator filter lists creators, narrows the rows and is kept in the URL', async ({
    page,
  }) => {
    const filter = page.getByRole('button', { name: /creator filter/i });
    await expect(filter).toContainText('Creator: any');
    await filter.click();
    await expect(page.getByRole('menuitemcheckbox')).toHaveText([
      'alice',
      'bob',
    ]);
    await page.getByRole('menuitemcheckbox', { name: 'bob' }).click();
    await page.keyboard.press('Escape');

    await expect(page).toHaveURL(/initiator=bob/);
    await expect(filter).toContainText('Creator: bob');
    await expect(rows(page)).toHaveCount(1);
    await expect(rows(page).first()).toContainText('Beta');

    await page.reload();
    await expect(rows(page)).toHaveCount(1);
  });

  test('two creators show both their sessions', async ({ page }) => {
    await page.getByRole('button', { name: /creator filter/i }).click();
    await page.getByRole('menuitemcheckbox', { name: 'alice' }).click();
    await page.getByRole('menuitemcheckbox', { name: 'bob' }).click();
    await page.keyboard.press('Escape');

    await expect(
      page.getByRole('button', { name: /creator filter/i })
    ).toContainText('2 selected');
    await expect(rows(page)).toHaveCount(3);
  });

  test('the Last run filter offers each outcome and "Not run"', async ({
    page,
  }) => {
    const filter = page.getByRole('button', { name: /last run filter/i });
    await filter.click();
    await expect(page.getByRole('menuitemcheckbox')).toHaveText([
      'Failed',
      'Not run',
      'Succeeded',
    ]);

    await page.getByRole('menuitemcheckbox', { name: 'Failed' }).click();
    await page.keyboard.press('Escape');
    await expect(page).toHaveURL(/lastrun=Failed/);
    await expect(rows(page)).toHaveCount(1);
    await expect(rows(page).first()).toContainText('Alpha');

    await filter.click();
    const failed = page.getByRole('menuitemcheckbox', { name: 'Failed' });
    await expect(failed).toBeChecked();
    await failed.click();
    await page.getByRole('menuitemcheckbox', { name: 'Not run' }).click();
    await page.keyboard.press('Escape');
    await expect(page).toHaveURL(/lastrun=Not(\+|%20)run/);
    // wait for the new row first: a count of 1 is also true of the row the Failed filter left
    await expect(rows(page).first()).toContainText('Gamma');
    await expect(rows(page)).toHaveCount(1);
  });

  test('Reset filters clears every filter and the URL', async ({ page }) => {
    await page.goto('/sessions?initiator=bob&lastrun=Succeeded');
    await expect(rows(page)).toHaveCount(1);

    await page.getByLabel('Reset filters').click();

    await expect(rows(page)).toHaveCount(3);
    await expect(page).not.toHaveURL(/initiator=|lastrun=/);
  });
});

test.describe('Session list - System toggle', () => {
  test('system sessions are hidden until the toggle is pressed', async ({
    page,
    mockSessionsApi,
  }) => {
    await mockSessionsApi(page, {
      sessions: [
        session('User session'),
        session('Supervisor machinery', { tags: ['yukta.internal'] }),
      ],
    });
    await page.goto('/sessions');

    const toggle = page.getByRole('button', { name: 'Show system sessions' });
    await expect(toggle).toHaveAttribute('aria-pressed', 'false');
    await expect(rows(page)).toHaveCount(1);
    await expect(
      page.getByText('Supervisor machinery', { exact: true })
    ).toBeHidden();

    await toggle.click();

    await expect(toggle).toHaveAttribute('aria-pressed', 'true');
    await expect(page).toHaveURL(/system=1/);
    await expect(rows(page)).toHaveCount(2);
    await expect(
      page.getByText('Supervisor machinery', { exact: true })
    ).toBeVisible();

    await toggle.click();
    await expect(rows(page)).toHaveCount(1);
    await expect(page).not.toHaveURL(/system=1/);
  });
});

test.describe('Session list - pagination across pages', () => {
  const many = Array.from({ length: 25 }, (_, i) =>
    session(`Session ${String(i + 1).padStart(2, '0')}`)
  );

  test.beforeEach(async ({ page, mockSessionsApi }) => {
    await mockSessionsApi(page, { sessions: many });
    await page.goto('/sessions');
  });

  test('pages forward and back with next, last, previous and first', async ({
    page,
  }) => {
    const next = page.getByRole('button', { name: 'Next page' });
    const previous = page.getByRole('button', { name: 'Previous page' });
    const first = page.getByRole('button', { name: 'First page' });
    const last = page.getByRole('button', { name: 'Last page' });

    await expect(page.getByText('1–10 of 25 items')).toBeVisible();
    await expect(rows(page)).toHaveCount(10);
    await expect(first).toBeDisabled();
    await expect(previous).toBeDisabled();

    await next.click();
    await expect(page.getByText('11–20 of 25 items')).toBeVisible();
    await expect(page.getByText('Session 11', { exact: true })).toBeVisible();
    await expect(first).toBeEnabled();

    await last.click();
    await expect(page.getByText('21–25 of 25 items')).toBeVisible();
    await expect(rows(page)).toHaveCount(5);
    await expect(next).toBeDisabled();
    await expect(last).toBeDisabled();

    await previous.click();
    await expect(page.getByText('11–20 of 25 items')).toBeVisible();

    await first.click();
    await expect(page.getByText('1–10 of 25 items')).toBeVisible();
  });

  test('Rows per page changes the page size', async ({ page }) => {
    await page.getByRole('button', { name: /^10/ }).click();
    await page.getByRole('menuitem', { name: '20' }).click();

    await expect(page.getByText('1–20 of 25 items')).toBeVisible();
    await expect(rows(page)).toHaveCount(20);

    await page.getByRole('button', { name: /^20/ }).click();
    await page.getByRole('menuitem', { name: '50' }).click();
    await expect(page.getByText('1–25 of 25 items')).toBeVisible();
    await expect(rows(page)).toHaveCount(25);
    await expect(
      page.getByRole('button', { name: 'Next page' })
    ).toBeDisabled();
  });

  test('the item count follows the search filter', async ({ page }) => {
    await page
      .getByPlaceholder(/Filter by name, ID or description/i)
      .fill('Session 2');

    await expect(page.getByText('1–6 of 6 items')).toBeVisible();
  });
});

test.describe('Session list - copy working directory', () => {
  test('the path button copies it and confirms', async ({
    page,
    context,
    mockSessionsApi,
  }) => {
    await context.grantPermissions(['clipboard-read', 'clipboard-write']);
    await mockSessionsApi(page, {
      sessions: [session('Copy me', { projectPath: '/srv/projects/copy-me' })],
    });
    await page.goto('/sessions');

    const copy = page.getByRole('button', {
      name: 'Copy working directory /srv/projects/copy-me',
    });
    await expect(copy).toContainText('content_copy');
    await copy.click();

    await expect(copy).toContainText('check');
    expect(await page.evaluate(() => navigator.clipboard.readText())).toBe(
      '/srv/projects/copy-me'
    );
    // the click copies, it does not open the session
    await expect(page).toHaveURL(/\/sessions$/);
  });
});
