// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { Page } from '@playwright/test';
import { test, expect } from '../fixtures';
import { mockMetricsApi } from '../utils';

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

/** Everything the Control page reads, with fixed values and a fixed clock so the page is repeatable. */
async function mockControlPage(page: Page) {
  await page.clock.setFixedTime(new Date('2026-07-26T14:30:00Z'));
  await mockMetricsApi(page, {
    'yukta.executions.running': 2,
    'runs.finished': 120,
    'runs.success': 100,
    'runs.failure': 20,
    'duration.p95': 1800,
  });
  const empty = [
    '**/api/supervisors',
    '**/api/supervisors/profiles',
    '**/api/supervisors/bindings',
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
  await page.route('**/api/supervisors/types', (route) =>
    route.fulfill(envelope('/types', ['rule-supervisor']))
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
      await expect(strip).toContainText('1.8 s');
      // The charts load lazily and show a skeleton of another height until they do
      await expect(page.locator('[aria-busy="true"]')).toHaveCount(0);
      await page.waitForLoadState('networkidle');
      await expect(page).toHaveScreenshot(`control-${name}.png`, {
        fullPage: true,
        maxDiffPixels: 6000,
        threshold: 0.2,
      });
    });
  }
});
