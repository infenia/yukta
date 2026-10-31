// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { Page } from '@playwright/test';
import { test, expect } from '../fixtures';

const envelope = (path: string, data: unknown) => ({
  status: 200,
  contentType: 'application/json',
  body: JSON.stringify({
    timestamp: '2026-07-26T14:23:23',
    status: 200,
    message: 'OK',
    path,
    data,
  }),
});

async function mockControlApi(
  page: Page,
  workers: unknown[],
  heartbeats: unknown[] = []
) {
  await page.route('**/api/supervisors', (route) =>
    route.fulfill(envelope('/supervisors', []))
  );
  await page.route('**/api/control/audit*', (route) =>
    route.fulfill(envelope('/audit', []))
  );
  await page.route('**/api/control/heartbeats', (route) =>
    route.fulfill(envelope('/heartbeats', heartbeats))
  );
  await page.route('**/api/edition', (route) =>
    route.fulfill(
      envelope('/edition', {
        edition: 'PLUS',
        license: 'x',
        features: [],
        source: null,
      })
    )
  );
  await page.route('**/api/workers', (route) =>
    route.fulfill(envelope('/workers', workers))
  );
}

test.describe('Control page', () => {
  test('lists the connected workers with their capacity use', async ({
    page,
  }) => {
    await mockControlApi(page, [
      {
        workerId: 'gpu-worker-1',
        pools: ['gpu'],
        pluginTypes: ['LLM_CALL', 'HTTP_REQUEST', 'SQL_QUERY', 'MAPPER'],
        capacity: 8,
        usedSlots: 6,
        draining: false,
        registeredAt: '2026-07-26T14:00:00Z',
        leaseUntil: '2099-01-01T00:00:00Z',
      },
    ]);
    await page.goto('/control');

    const row = page.getByRole('row', { name: /gpu-worker-1/ });
    await expect(row).toContainText('6/8');
    await expect(row).toContainText('+1');
    await expect(row).toContainText('gpu');
  });

  test('says when no workers are connected', async ({ page }) => {
    await mockControlApi(page, []);
    await page.goto('/control');

    await expect(page.getByText('No workers connected.')).toBeVisible();
  });

  test('sends a command to a node and shows the response', async ({ page }) => {
    await mockControlApi(
      page,
      [],
      [
        {
          workflowId: 'wf-1',
          executionId: 'exec-1111-aaaa',
          nodeId: 'ingest',
          timestamp: new Date().toISOString(),
        },
      ]
    );
    let sent: unknown;
    await page.route(
      '**/api/control/workflows/wf-1/nodes/ingest/command',
      (route) => {
        sent = route.request().postDataJSON();
        return route.fulfill(
          envelope('/command', { payload: { flushed: true } })
        );
      }
    );
    await page.goto('/control');

    const row = page.getByRole('row', { name: /ingest/ });
    await expect(row).toContainText('exec-111');
    await row.getByRole('button', { name: 'Send command to ingest' }).click();
    await page.getByLabel('Command (JSON object)').fill('{"action":"flush"}');
    await page.getByRole('button', { name: 'Send', exact: true }).click();

    await expect(page.getByText('"flushed": true')).toBeVisible();
    expect(sent).toEqual({ action: 'flush' });
  });

  test('shows the live activity feed and filters it', async ({ page }) => {
    await mockControlApi(page, []);
    const frame = (type: string, severity: string, event: object) =>
      `data: ${JSON.stringify({ type, severity, rootExecutionId: 'r', depth: 0, event })}\n\n`;
    await page.route('**/api/events/stream*', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'text/event-stream',
        body:
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
          }),
      })
    );
    await page.goto('/control?tab=activity');

    await expect(page.getByText('ingest → RUNNING')).toBeVisible();
    await expect(page.getByText('load → FAILURE')).toBeVisible();
    await page.getByRole('button', { name: 'ERROR' }).click();
    await expect(page.getByText('ingest → RUNNING')).toHaveCount(0);
    await expect(page.getByText('load → FAILURE')).toBeVisible();
    await expect(page).toHaveURL(/severity=ERROR/);
  });

  test('shows the execution limits and changes them', async ({ page }) => {
    await mockControlApi(page, []);
    let limits = {
      maxConcurrent: 4,
      maxPerWorkflow: 0,
      running: 3,
      queued: 1,
      queueCapacity: 100,
    };
    let posted = '';
    await page.route('**/api/scheduler/limits*', (route) => {
      if (route.request().method() === 'POST') {
        posted = new URL(route.request().url()).search;
        limits = { ...limits, maxConcurrent: 8, maxPerWorkflow: 2 };
        return route.fulfill(envelope('/limits', {}));
      }
      return route.fulfill(envelope('/limits', limits));
    });
    await page.goto('/control');

    await expect(page.getByText('Unlimited')).toBeVisible();
    await page.getByRole('button', { name: 'Edit limits' }).click();
    await page.getByLabel(/Max concurrent/).fill('8');
    await page.getByLabel(/Max per workflow/).fill('2');
    await page.getByRole('button', { name: 'Save' }).click();
    await page.getByRole('button', { name: 'Confirm and save' }).click();

    await expect(page.getByRole('dialog')).toHaveCount(0);
    expect(posted).toBe('?maxConcurrent=8&maxPerWorkflow=2');
    await expect(page.getByText('of 8', { exact: true })).toBeVisible();
  });

  test('shows the dashboard: numbers, a slow live run and the limit knob', async ({
    page,
  }) => {
    await mockControlApi(page, []);
    await page.route('**/api/metrics/snapshot*', (route) =>
      route.fulfill(
        envelope('/metrics/snapshot', {
          time: Date.now(),
          values: { 'runs.finished': 120, 'duration.p95': 1800 },
        })
      )
    );
    await page.route('**/api/metrics/runs/live', (route) =>
      route.fulfill(
        envelope('/metrics/runs/live', [
          {
            executionId: 'run-1',
            sessionId: 's1',
            workflowId: 'wf-slow',
            startedAt: Date.now() - 90_000,
            elapsedMs: 90_000,
            nodesDone: 1,
            nodesSeen: 4,
            p95Ms: 30_000,
          },
        ])
      )
    );
    await page.route('**/api/metrics/heatmap*', (route) =>
      route.fulfill(
        envelope('/metrics/heatmap', [
          { time: Date.now(), runs: 10, failed: 1, failedRuns: ['x'] },
        ])
      )
    );
    let posted = '';
    await page.route('**/api/scheduler/limits*', (route) => {
      if (route.request().method() === 'POST') {
        posted = new URL(route.request().url()).search;
        return route.fulfill(envelope('/scheduler/limits', null));
      }
      return route.fulfill(
        envelope('/scheduler/limits', {
          maxConcurrent: 4,
          maxPerWorkflow: 0,
          running: 2,
          queued: 0,
          queueCapacity: 100,
        })
      );
    });
    await page.goto('/control');

    const strip = page.getByTestId('command-strip');
    await expect(strip).toContainText('2.0');
    await expect(strip).toContainText('1.8 s');
    await expect(strip).toContainText('90%');
    const slow = page.getByRole('listitem').filter({ hasText: 'wf-slow' });
    await expect(slow).toContainText('(slow)');
    await expect(slow).toHaveAttribute('data-slow', 'true');

    const knob = page.getByRole('slider', { name: /Concurrent run limit/ });
    await expect(knob).toHaveAttribute('aria-valuenow', '4');
    await knob.focus();
    await page.keyboard.press('ArrowUp');
    await expect.poll(() => posted).toBe('?maxConcurrent=5&maxPerWorkflow=0');
  });
});
