// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { test, expect } from '../fixtures';
import { toggleOptions } from '../utils';
import { envelope } from '../api-envelope';
import { RunsPage } from '../pages/RunsPage';

const run = (n: number, status: string) => ({
  sessionId: 'SESSION_A92_XP_2024',
  executionId: `${String(n).padStart(4, '0')}abcd-run`,
  workflowId: 'wf-982-xk-11',
  status,
  startTime: '2026-07-26T14:22:01',
  endTime: '2026-07-26T14:22:10',
});

test.describe('Runs page', () => {
  test.beforeEach(async ({ page }) => {
    await page.route('**/api/sessions/summaries*', (route) =>
      route.fulfill(envelope('/summaries', []))
    );
    await page.route('**/api/executions?*', (route) => {
      const url = new URL(route.request().url());
      const status = url.searchParams.get('status');
      const limit = Number(url.searchParams.get('limit'));
      const all = Array.from({ length: 150 }, (_, i) =>
        run(i, i % 2 ? 'FAILURE' : 'SUCCESS')
      );
      return route.fulfill(
        envelope(
          '/executions',
          all.filter((r) => !status || r.status === status).slice(0, limit)
        )
      );
    });
  });

  test('filters by status, loads more and opens a run', async ({
    page,
    mockWorkflowApi,
  }) => {
    await mockWorkflowApi(page);
    const runs = new RunsPage(page);
    await runs.goto();
    await expect(runs.run('0000abcd')).toBeVisible();

    await toggleOptions(runs.status, ['FAILURE']);
    await expect(page).toHaveURL(/status=FAILURE/);
    await expect(runs.run('0001abcd')).toBeVisible();
    await expect(runs.run('0000abcd')).toHaveCount(0);

    await page.getByLabel('Clear Status').click();
    await expect(runs.rows).toHaveCount(101);
    await runs.loadMore.click();
    await expect(runs.rows).toHaveCount(151);

    await runs.run('0000abcd').click();
    await expect(page).toHaveURL(/\/workflow\/wf-982-xk-11\?exec=0000abcd-run/);
  });

  test('old /history links land on /runs with their query', async ({
    page,
  }) => {
    const runs = new RunsPage(page);
    await runs.gotoHistory('SUCCESS');
    await expect(page).toHaveURL(/\/runs\?status=SUCCESS/);
  });
});
