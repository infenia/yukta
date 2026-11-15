// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { Page } from '@playwright/test';
import { test, expect } from '../fixtures';

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

const items = [
  item({
    id: 'q1',
    key: 'input:r1',
    kind: 'ACTION',
    severity: 'WARN',
    category: 'input',
    title: 'Deploy to production?',
    body: null,
    inputRequestId: 'r1',
    input: request(),
    seq: 4,
  }),
  item({
    id: 'q2',
    key: 'input:r2',
    kind: 'ACTION',
    severity: 'WARN',
    category: 'input',
    title: 'Why did the gate fail?',
    body: null,
    sessionId: 's2',
    inputRequestId: 'r2',
    input: request({ id: 'r2', kind: 'TEXT', prompt: 'Explain the rollback' }),
    seq: 3,
  }),
  item({ id: 'a1', severity: 'ERROR', seq: 2 }),
  item({
    id: 'done',
    key: 'input:r0',
    kind: 'ACTION',
    severity: 'WARN',
    category: 'input',
    title: 'Old question',
    body: null,
    state: 'RESOLVED',
    resolution: 'answered by key:ann: approve',
    read: true,
    seq: 1,
  }),
];

async function mockInbox(page: Page, list: unknown[] = items) {
  await page.route('**/api/notifications/stream', (route) =>
    route.fulfill({ status: 200, contentType: 'text/event-stream', body: '' })
  );
  await page.route('**/api/notifications/preferences', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        status: 200,
        message: 'OK',
        data: { levels: {}, mutedSessions: [] },
      }),
    })
  );
  await page.route('**/api/notifications?*', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ status: 200, message: 'OK', data: list }),
    })
  );
}

test.describe('Inbox page', () => {
  test('shows the three tabs with counts and keeps a resolved question under Resolved', async ({
    page,
  }) => {
    await mockInbox(page);
    await page.goto('/inbox');

    await expect(page.getByRole('heading', { name: 'Inbox' })).toBeVisible();
    await expect(page.getByRole('tab', { name: /Needs action/ })).toContainText(
      '2'
    );
    await expect(page.getByRole('tab', { name: /All/ })).toContainText('4');
    await expect(page.getByText('Deploy to production?').first()).toBeVisible();

    await page.getByRole('tab', { name: /Resolved/ }).click();
    await expect(page.getByText('Old question')).toBeVisible();
    await expect(
      page.getByRole('status').filter({ hasText: 'answered by' })
    ).toContainText('answered by key:ann: approve');
  });

  test('filters by severity', async ({ page }) => {
    await mockInbox(page);
    await page.goto('/inbox');
    await page.getByRole('tab', { name: /All/ }).click();

    await page.getByLabel('Severity').selectOption('ERROR');
    await expect(page.getByText('Nightly run failed')).toBeVisible();
    await expect(page.getByText('Deploy to production?')).toHaveCount(0);
  });

  test('a deep link opens the sheet for a question that needs a text answer', async ({
    page,
  }) => {
    await mockInbox(page);
    await page.goto('/inbox?item=q2');

    const sheet = page.getByRole('dialog');
    await expect(sheet).toContainText('Explain the rollback');
    await sheet.getByRole('button', { name: 'Close' }).click();
    await expect(page).not.toHaveURL(/item=/);
  });

  test('says when a linked item is not available', async ({ page }) => {
    await mockInbox(page);
    await page.goto('/inbox?item=gone');
    await expect(page.getByRole('alert')).toContainText('not available to you');
  });

  test('opens the preferences sheet', async ({ page }) => {
    await mockInbox(page);
    await page.goto('/inbox');
    await page.getByRole('button', { name: 'Preferences' }).click();
    await expect(page.getByRole('dialog')).toContainText(
      'Notification preferences'
    );
    await expect(
      page.getByRole('combobox', { name: 'input', exact: true })
    ).toBeVisible();
  });

  test('fits a phone without scrolling sideways', async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 800 });
    await mockInbox(page);
    await page.goto('/inbox');
    await expect(page.getByRole('heading', { name: 'Inbox' })).toBeVisible();

    const overflow = await page.evaluate(
      () =>
        document.documentElement.scrollWidth -
        document.documentElement.clientWidth
    );
    expect(overflow).toBeLessThanOrEqual(0);
  });
});
