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
  nodes: string[] = []
) {
  await page.route('**/api/supervisors', (route) =>
    route.fulfill(envelope('/supervisors', []))
  );
  await page.route('**/api/control/audit*', (route) =>
    route.fulfill(envelope('/audit', []))
  );
  await page.route('**/api/control/nodes', (route) =>
    route.fulfill(envelope('/nodes', nodes))
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
    await mockControlApi(page, [], ['wf-1:ingest']);
    await page.route('**/api/control/workflows/wf-1/nodes/ingest/heartbeat', (route) =>
      route.fulfill(envelope('/heartbeat', { beat: 1 }))
    );
    let sent: unknown;
    await page.route('**/api/control/workflows/wf-1/nodes/ingest/command', (route) => {
      sent = route.request().postDataJSON();
      return route.fulfill(envelope('/command', { payload: { flushed: true } }));
    });
    await page.goto('/control');

    await page.getByLabel('Workflow', { exact: true }).selectOption('wf-1');
    await page.getByLabel('Node', { exact: true }).selectOption('ingest');
    await expect(page.getByText('"beat": 1')).toBeVisible();
    await page.getByRole('button', { name: 'Send command…' }).click();
    await page.getByLabel('Command (JSON object)').fill('{"action":"flush"}');
    await page.getByRole('button', { name: 'Send', exact: true }).click();

    await expect(page.getByText('"flushed": true')).toBeVisible();
    expect(sent).toEqual({ action: 'flush' });
  });
});
