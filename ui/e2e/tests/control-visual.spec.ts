// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { Page } from '@playwright/test';
import { test, expect } from '../fixtures';
import { mockMetricsApi } from '../utils';
import { envelope } from '../api-envelope';

/** Everything the Control page reads, with fixed values and a fixed clock so the page is repeatable. */
async function mockControlPage(page: Page) {
  await page.clock.setFixedTime(new Date('2026-07-26T14:30:00Z'));
  await mockMetricsApi(page, {
    'yukta.executions.running': 2,
    'runs.finished': 120,
    'runs.success': 100,
    'runs.failure': 20,
    'overhead.handoff.p50': 2,
    'overhead.handoff.p95': 4,
    'overhead.start.p95': 12,
  });
  const empty = [
    '**/api/supervisors',
    '**/api/supervisors/status',
    '**/api/supervisors/assignments*',
    '**/api/supervisors/decisions*',
    '**/api/control/audit*',
    '**/api/control/heartbeats',
    '**/api/workers',
    '**/api/metrics/heatmap*',
    '**/api/metrics/modules*',
    '**/api/metrics/annotations*',
    '**/api/metrics/runs/live',
    '**/api/sessions/summaries*',
  ];
  for (const pattern of empty) {
    await page.route(pattern, (route) => route.fulfill(envelope(pattern, [])));
  }
  // the slowest nodes name the node, its workflow and its session
  await page.route('**/api/metrics/nodes*', (route) =>
    route.fulfill(
      envelope('/metrics/nodes', [
        {
          sessionId: 'ci',
          workflowId: 'check',
          nodeId: 'gradle',
          module: 'PROCESS_EXECUTOR',
          runs: 14,
          failures: 1,
          p50Ms: 220_000,
          p95Ms: 410_000,
          lastExecutionId: 'run-1',
        },
        {
          sessionId: 'ci',
          workflowId: 'check',
          nodeId: 'lint',
          module: 'PROCESS_EXECUTOR',
          runs: 14,
          failures: 0,
          p50Ms: 21_000,
          p95Ms: 33_000,
          lastExecutionId: 'run-1',
        },
      ])
    )
  );
  await page.route('**/api/supervisor-engines', (route) =>
    route.fulfill(envelope('/types', [{ type: 'rule-supervisor' }]))
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
  await page.route('**/api/scheduler/limits*', (route) =>
    route.fulfill(
      envelope('/scheduler/limits', {
        maxConcurrent: 4,
        maxPerWorkflow: 0,
        running: 2,
        queued: 0,
        queueCapacity: 100,
      })
    )
  );
}

test.describe('Control dashboard screenshots', () => {
  // the chart's time labels follow the zone; CI runs in UTC
  test.use({ timezoneId: 'UTC' });
  for (const [name, scheme, width] of [
    ['light', 'light', 1280],
    ['dark', 'dark', 1280],
    ['phone', 'light', 390],
  ] as const) {
    test(`should match screenshot - ${name}`, async ({ page }) => {
      await page.emulateMedia({ colorScheme: scheme });
      await page.setViewportSize({ width, height: 900 });
      await mockControlPage(page);
      await page.goto('/control');
      const strip = page.getByTestId('command-strip');
      await expect(strip).toContainText('4 ms');
      // The charts load lazily and show a skeleton of another height until they do
      await expect(page.locator('[aria-busy="true"]')).toHaveCount(0);
      await page.waitForLoadState('networkidle');
      await expect(page).toHaveScreenshot(`control-${name}.png`, {
        fullPage: true,
        // chart axes carry the wall-clock time and wrap by font metrics
        mask: [page.locator('.recharts-wrapper')],
        maxDiffPixels: 6000,
        threshold: 0.2,
      });
    });
  }
});
