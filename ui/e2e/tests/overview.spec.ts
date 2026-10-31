// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

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

const run = (status: string, executionId: string) => ({
  sessionId: 'payments',
  workflowId: 'deploy',
  executionId,
  status,
  startTime: '2026-07-26T14:22:01',
  endTime: '2026-07-26T14:22:10',
});

test.describe('Overview', () => {
  test.beforeEach(async ({ page }) => {
    // 'since 3 minutes ago' must read the same in every run
    await page.clock.setFixedTime(new Date('2026-07-26T14:23:23'));
    await page.route('**/api/executions/counts', (route) =>
      route.fulfill(envelope('/counts', { running: 2, queued: 1, failed: 1 }))
    );
    await page.route('**/api/executions?*', (route) =>
      route.fulfill(
        envelope(
          '/executions',
          route.request().url().includes('ABORTED')
            ? [run('ABORTED', 'abort-1')]
            : [run('FAILURE', 'fail-1')]
        )
      )
    );
    await page.route('**/api/approvals?*', (route) =>
      route.fulfill(
        envelope('/approvals', [
          {
            sessionId: 'payments',
            executionId: 'exec-1',
            workflowId: 'deploy',
            nodeId: 'gate',
            name: 'go',
            since: '2026-07-26T14:20:23',
          },
        ])
      )
    );
    await page.route('**/api/scheduler/limits', (route) =>
      route.fulfill(
        envelope('/limits', {
          maxConcurrent: 4,
          maxPerWorkflow: 0,
          running: 2,
          queued: 1,
          queueCapacity: 10,
        })
      )
    );
    await page.route('**/api/workers', (route) =>
      route.fulfill(envelope('/workers', []))
    );
  });

  test('answers what runs, what failed and what needs me', async ({ page }) => {
    await page.goto('/');
    await expect(
      page.getByRole('heading', { name: 'Overview', level: 1 })
    ).toBeVisible();
    await expect(page.getByRole('link', { name: /Running/ })).toContainText(
      '2'
    );
    await expect(
      page.getByRole('region', { name: 'Needs attention' })
    ).toContainText('gate');
    await expect(
      page.getByRole('region', { name: 'Recent failures' })
    ).toContainText('payments/deploy');
    await expect(page.getByRole('button', { name: /Recover/ })).toBeVisible();
  });

  test('rejecting asks for a reason first', async ({ page }) => {
    await page.goto('/');
    await page.getByRole('button', { name: 'Reject' }).click();
    await expect(page.getByLabel('Reason (optional)')).toBeVisible();
  });

  test('the session list is at /sessions, and old / queries go there', async ({
    page,
  }) => {
    await page.route('**/api/sessions/summaries*', (route) =>
      route.fulfill(envelope('/summaries', []))
    );
    await page.goto('/?q=abc');
    await expect(page).toHaveURL(/\/sessions\?q=abc/);
  });

  const screenshotOptions = { maxDiffPixels: 2000, threshold: 0.2 };

  for (const [name, scheme, width] of [
    ['light', 'light', 1280],
    ['dark', 'dark', 1280],
    ['phone', 'light', 390],
  ] as const) {
    test(`should match screenshot - ${name}`, async ({ page }) => {
      await page.emulateMedia({ colorScheme: scheme });
      await page.setViewportSize({ width, height: 900 });
      await page.goto('/');
      await expect(
        page.getByRole('region', { name: 'Needs attention' })
      ).toContainText('gate');
      await expect(
        page.getByRole('region', { name: 'Recent failures' })
      ).toContainText('payments/deploy');
      await expect(page).toHaveScreenshot(`overview-${name}.png`, {
        ...screenshotOptions,
        // the event stream is empty in tests, so its panel text depends on timing
        mask: [page.getByRole('region', { name: 'Live activity' })],
      });
    });
  }
});

test.describe('Hero backdrop behind the page headers', () => {
  for (const [path, heading] of [
    ['/', 'Overview'],
    ['/runs', 'Runs'],
    ['/plugins', 'Plugins'],
    ['/control', 'Control bus'],
  ] as const) {
    for (const width of [1280, 390]) {
      test(`${path} at ${width} px has the backdrop and no horizontal scroll`, async ({
        page,
      }) => {
        await page.setViewportSize({ width, height: 800 });
        await page.goto(path);
        await expect(
          page.getByRole('heading', { name: heading, level: 1 })
        ).toBeVisible();

        const backdrop = page.getByTestId('hero-backdrop');
        await expect(backdrop).toHaveCount(1);
        await expect(backdrop).toHaveAttribute('aria-hidden', 'true');
        const sizes = await page.evaluate(() => ({
          layer:
            document
              .querySelector('[data-testid="hero-backdrop"]')
              ?.getBoundingClientRect().width ?? -1,
          main:
            document.querySelector('main')?.getBoundingClientRect().width ?? -2,
          overflow:
            document.documentElement.scrollWidth -
            document.documentElement.clientWidth,
        }));
        // as wide as the page content area, and nothing sticks out
        expect(sizes.layer).toBe(sizes.main);
        expect(sizes.overflow).toBeLessThanOrEqual(0);
      });
    }
  }
});
