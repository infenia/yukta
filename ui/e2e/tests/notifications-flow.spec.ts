// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { Page } from '@playwright/test';
import { test, expect } from '../fixtures';
import { envelope } from '../api-envelope';

const at = '2026-11-15T10:00:00Z';

const request = (over: Record<string, unknown> = {}) => ({
  id: 'r1',
  sessionId: 's1',
  workflowId: 'release',
  executionId: 'e1',
  nodeId: 'gate',
  state: 'OPEN',
  version: 1,
  kind: 'APPROVAL',
  prompt: 'Deploy to production?',
  context: {},
  options: [],
  responseSchema: null,
  risk: 'LOW',
  tier: 'operator',
  advice: null,
  deadline: null,
  createdAt: at,
  updatedAt: at,
  answer: null,
  ...over,
});

const item = (over: Record<string, unknown> = {}) => ({
  id: 'n1',
  key: 'k1',
  kind: 'INFO',
  severity: 'INFO',
  category: 'run.failed',
  title: 'Nightly run failed',
  body: 'node export timed out',
  sessionId: 's1',
  workflowId: 'nightly',
  executionId: 'e9',
  nodeId: 'export',
  inputRequestId: null,
  state: 'ACTIVE',
  resolution: null,
  count: 1,
  seq: 1,
  createdAt: at,
  updatedAt: at,
  read: false,
  dismissed: false,
  input: null,
  ...over,
});

const question = (
  id: string,
  prompt: string,
  over: Record<string, unknown> = {}
) =>
  item({
    id: `q-${id}`,
    key: `input:${id}`,
    kind: 'ACTION',
    severity: 'WARN',
    category: 'input',
    title: prompt,
    body: null,
    inputRequestId: id,
    input: request({ id, prompt, ...over }),
    seq: 5,
  });

const APPROVAL = question('r1', 'Deploy to production?');
const TEXT = question('r2', 'Why did the gate fail?', { kind: 'TEXT' });
const FAILED = item({ id: 'a1', severity: 'ERROR', seq: 3 });
const SLOW = item({
  id: 'a2',
  key: 'k2',
  title: 'Queue is slow',
  body: null,
  severity: 'WARN',
  category: 'scheduler',
  sessionId: 's2',
  seq: 2,
});

interface Mock {
  reads: Record<string, unknown>[];
  prefs: Record<string, unknown>[];
}

async function mockNotifications(
  page: Page,
  list: unknown[],
  {
    stream = '',
    prefs = { levels: {}, mutedSessions: [] },
    prefsStatus = 200,
  }: { stream?: string; prefs?: object; prefsStatus?: number } = {}
): Promise<Mock> {
  const calls: Mock = { reads: [], prefs: [] };
  await page.route('**/api/notifications/stream', (r) =>
    r.fulfill({ status: 200, contentType: 'text/event-stream', body: stream })
  );
  await page.route('**/api/notifications/read', (r) => {
    calls.reads.push(r.request().postDataJSON() ?? {});
    return r.fulfill(envelope('/read', { marked: 1 }));
  });
  await page.route('**/api/notifications/preferences', (r) => {
    if (r.request().method() === 'PUT') {
      calls.prefs.push(r.request().postDataJSON());
      return r.fulfill(envelope('/preferences', prefs));
    }
    return prefsStatus === 200
      ? r.fulfill(envelope('/preferences', prefs))
      : r.fulfill({
          status: prefsStatus,
          contentType: 'application/json',
          body: '{"message":"no preferences"}',
        });
  });
  await page.route('**/api/notifications?*', (r) =>
    r.fulfill(envelope('/notifications', list))
  );
  return calls;
}

const bell = (page: Page) =>
  page.getByRole('button', { name: /^Notifications/ });
const popover = (page: Page) => page.getByRole('dialog').first();

test.describe('Notifications - the bell', () => {
  test('is named by what waits: questions first, then unread alerts, else plain', async ({
    page,
  }) => {
    await mockNotifications(page, [APPROVAL, TEXT, FAILED]);
    await page.goto('/sessions');
    await expect(bell(page)).toHaveAccessibleName(
      'Notifications, 2 need action'
    );
    await expect(page.getByTestId('bell-count')).toHaveText('2');

    await mockNotifications(page, [FAILED, SLOW]);
    await page.reload();
    await expect(bell(page)).toHaveAccessibleName('Notifications, 2 unread');
    await expect(page.getByTestId('bell-count')).toHaveCount(0);

    await mockNotifications(page, []);
    await page.reload();
    await expect(bell(page)).toHaveAccessibleName('Notifications');
  });

  test('opens a popover with the questions that wait and the newest alerts', async ({
    page,
  }) => {
    await mockNotifications(page, [APPROVAL, TEXT, FAILED, SLOW]);
    await page.goto('/sessions');

    await bell(page).click();

    const pop = popover(page);
    await expect(
      pop.getByRole('heading', { name: 'Needs action · 2' })
    ).toBeVisible();
    await expect(pop).toContainText('Deploy to production?');
    await expect(pop).toContainText('Why did the gate fail?');
    await expect(pop).toContainText('low risk');
    await expect(pop.getByRole('heading', { name: 'Recent' })).toBeVisible();
    await expect(pop).toContainText('Nightly run failed');
    await expect(pop).toContainText('Queue is slow');
  });

  test('a question that needs typing links to the inbox to answer it', async ({
    page,
  }) => {
    await mockNotifications(page, [TEXT]);
    await page.goto('/sessions');
    await bell(page).click();

    await popover(page).getByRole('link', { name: 'Answer…' }).click();

    await expect(page).toHaveURL(/\/inbox\?item=q-r2$/);
  });

  test('says when nothing waits and when there are no alerts', async ({
    page,
  }) => {
    await mockNotifications(page, []);
    await page.goto('/sessions');

    await bell(page).click();

    await expect(
      popover(page).getByRole('heading', { name: 'Needs action · 0' })
    ).toBeVisible();
    await expect(
      popover(page).getByText('Nothing waits for you.')
    ).toBeVisible();
    await expect(popover(page).getByText('No alerts.')).toBeVisible();
    await expect(
      popover(page).getByRole('button', { name: 'Mark all read' })
    ).toBeDisabled();
  });

  test('Mark all read tells the server and clears the unread count', async ({
    page,
  }) => {
    const calls = await mockNotifications(page, [FAILED, SLOW]);
    await page.goto('/sessions');
    await expect(bell(page)).toHaveAccessibleName('Notifications, 2 unread');
    await bell(page).click();

    await popover(page).getByRole('button', { name: 'Mark all read' }).click();

    await expect(bell(page)).toHaveAccessibleName('Notifications');
    await expect(
      popover(page).getByRole('button', { name: 'Mark all read' })
    ).toBeDisabled();
    expect(calls.reads).toHaveLength(1);
  });
});

test.describe('Notifications - toasts', () => {
  const created = (n: Record<string, unknown>) =>
    `data: ${JSON.stringify({
      type: 'created',
      notification: n,
      notificationId: n['id'],
      readAt: null,
      dismissedAt: null,
    })}\n\n`;

  test('a new question appears bottom right with its risk and a link to it, and can be dismissed', async ({
    page,
  }) => {
    await mockNotifications(page, [], { stream: created(APPROVAL) });
    await page.goto('/sessions');

    const toasts = page.getByRole('region', { name: 'New notifications' });
    await expect(toasts).toBeVisible();
    const toast = toasts.getByTestId('toast');
    await expect(toast).toContainText('Deploy to production?');
    await expect(toast).toContainText('low risk');
    await expect(toast.getByRole('link', { name: 'View' })).toHaveAttribute(
      'href',
      '/inbox?item=q-r1'
    );

    await toast.getByRole('button', { name: 'Dismiss' }).click();
    await expect(toasts).toHaveCount(0);
  });

  test('an error alert is announced assertively and View opens it in the inbox', async ({
    page,
  }) => {
    await mockNotifications(page, [], { stream: created(FAILED) });
    await page.goto('/sessions');

    const toast = page.getByTestId('toast');
    await expect(toast).toHaveAttribute('role', 'alert');
    await expect(toast).toContainText('Nightly run failed');
    await toast.getByRole('link', { name: 'View' }).click();

    await expect(page).toHaveURL(/\/inbox\?item=a1$/);
    await expect(page.getByTestId('toast')).toHaveCount(0);
  });

  test('shows at most three toasts, newest on top', async ({ page }) => {
    const four = [1, 2, 3, 4]
      .map((n) =>
        created(
          item({ id: `t${n}`, key: `t${n}`, title: `Alert ${n}`, seq: n })
        )
      )
      .join('');
    await mockNotifications(page, [], { stream: four });
    await page.goto('/sessions');

    await expect(page.getByTestId('toast')).toHaveCount(3);
    await expect(page.getByTestId('toast').first()).toContainText('Alert 4');
  });

  test('an alert that was already read does not pop up', async ({ page }) => {
    await mockNotifications(page, [], {
      stream: created(item({ id: 'old', read: true })),
    });
    await page.goto('/sessions');
    await expect(bell(page)).toBeVisible();

    await expect(page.getByTestId('toast')).toHaveCount(0);
  });
});

test.describe('Notifications - the inbox filters', () => {
  test.beforeEach(async ({ page }) => {
    await mockNotifications(page, [APPROVAL, TEXT, FAILED, SLOW]);
    await page.goto('/inbox');
    await page.getByRole('tab', { name: /All/ }).click();
  });

  test('the session filter narrows the list to one session', async ({
    page,
  }) => {
    await expect(page.getByLabel('Session').locator('option')).toContainText([
      'All sessions',
      's1',
      's2',
    ]);

    await page.getByLabel('Session').selectOption('s2');

    await expect(page.getByText('Queue is slow')).toBeVisible();
    await expect(page.getByText('Nightly run failed')).toHaveCount(0);
  });

  test('the category filter narrows the list to one kind of notification', async ({
    page,
  }) => {
    await page.getByLabel('Category').selectOption('scheduler');

    await expect(page.getByText('Queue is slow')).toBeVisible();
    await expect(page.getByText('Deploy to production?')).toHaveCount(0);
  });

  test('the severity filter offers Warning or above, and shows nothing for no match', async ({
    page,
  }) => {
    await page
      .getByLabel('Severity')
      .selectOption({ label: 'Warning or above' });
    await expect(page.getByText('Queue is slow')).toBeVisible();
    await expect(page.getByText('Nightly run failed')).toBeVisible();

    await page.getByLabel('Category').selectOption('scheduler');
    await page.getByLabel('Session').selectOption('s1');
    await expect(page.getByText('Nothing here')).toBeVisible();
  });

  test('Resolved says when nothing has ended yet', async ({ page }) => {
    await page.getByRole('tab', { name: /Resolved/ }).click();

    await expect(page.getByText('Nothing has ended yet')).toBeVisible();
  });
});

test.describe('Notifications - the preferences', () => {
  test('lists every category with its level, and the input category cannot be switched off', async ({
    page,
  }) => {
    await mockNotifications(page, [], {
      prefs: { levels: { 'run.failed': 'OFF' }, mutedSessions: ['s2'] },
    });
    await page.goto('/inbox');

    await page.getByRole('button', { name: 'Preferences' }).click();

    const sheet = page.getByRole('dialog', {
      name: 'Notification preferences',
    });
    await expect(sheet.getByText('What reaches me')).toBeVisible();
    await expect(
      sheet.getByRole('combobox', { name: 'run.failed' })
    ).toHaveValue('OFF');
    await expect(
      sheet
        .getByRole('combobox', { name: 'input', exact: true })
        .locator('option')
    ).toHaveText(['Inbox', 'Inbox and desktop']);
    await expect(
      sheet.getByRole('combobox', { name: 'run.failed' }).locator('option')
    ).toHaveText(['Off', 'Inbox', 'Inbox and desktop']);
    await expect(sheet.getByText('Muted sessions')).toBeVisible();
    await expect(sheet.getByRole('checkbox', { name: 's2' })).toBeChecked();
    await expect(sheet.getByText('This device')).toBeVisible();
    await expect(
      sheet.getByRole('checkbox', { name: /Show desktop notifications/ })
    ).toBeVisible();
    await expect(
      sheet.getByRole('checkbox', { name: /Show the title and the workflow/ })
    ).toBeVisible();
  });

  test('Save sends the changed levels and muted sessions, and closes the sheet', async ({
    page,
  }) => {
    const calls = await mockNotifications(page, [APPROVAL, SLOW]);
    await page.goto('/inbox');
    await page.getByRole('button', { name: 'Preferences' }).click();
    const sheet = page.getByRole('dialog', {
      name: 'Notification preferences',
    });

    await sheet
      .getByRole('combobox', { name: 'run.failed' })
      .selectOption('OFF');
    await sheet.getByRole('checkbox', { name: 's2' }).check();
    await sheet.getByRole('button', { name: 'Save' }).click();

    await expect(sheet).toBeHidden();
    expect(calls.prefs).toHaveLength(1);
    expect(calls.prefs[0]).toMatchObject({
      levels: { 'run.failed': 'OFF' },
      mutedSessions: ['s2'],
    });
  });

  test('Close leaves the preferences as they were', async ({ page }) => {
    const calls = await mockNotifications(page, []);
    await page.goto('/inbox');
    await page.getByRole('button', { name: 'Preferences' }).click();
    const sheet = page.getByRole('dialog', {
      name: 'Notification preferences',
    });

    await sheet.getByRole('button', { name: 'Close' }).click();

    await expect(sheet).toBeHidden();
    expect(calls.prefs).toHaveLength(0);
  });

  test('says when the preferences cannot be read', async ({ page }) => {
    await mockNotifications(page, [], { prefsStatus: 500 });
    await page.goto('/inbox');

    await page.getByRole('button', { name: 'Preferences' }).click();

    await expect(
      page.getByText('The preferences could not be read.')
    ).toBeVisible({
      timeout: 15_000,
    });
  });
});
