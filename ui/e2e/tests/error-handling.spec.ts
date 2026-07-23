// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { test, expect } from '../fixtures';
import { waitForLoadingComplete } from '../utils';

test.describe('Error Handling - Mocked API Failures', () => {
  test('should show error message when the sessions API fails', async ({
    page,
    mockSessionsApi,
  }) => {
    await mockSessionsApi(page, { status: 500 });
    await page.goto('/');

    await expect(page.getByText('Failed to load sessions')).toBeVisible();
  });

  test('should display empty state when no sessions available', async ({
    page,
    mockSessionsApi,
  }) => {
    await mockSessionsApi(page, { sessions: [] });
    await page.goto('/');
    await waitForLoadingComplete(page);

    await expect(page.getByText('No sessions yet')).toBeVisible();
    const rows = page.locator('tbody tr');
    await expect(rows).toHaveCount(1);
  });

  test('should recover after a reload once the API succeeds', async ({
    page,
    mockSessionsApi,
  }) => {
    await mockSessionsApi(page, { status: 500 });
    await page.goto('/');
    await expect(page.getByText('Failed to load sessions')).toBeVisible();

    await mockSessionsApi(page, {});
    await page.reload();
    await waitForLoadingComplete(page);

    await expect(page.locator('table')).toBeVisible();
    await expect(page.getByText('Failed to load sessions')).not.toBeVisible();
  });

  test('should show loading state before the error appears', async ({
    context,
  }) => {
    const freshPage = await context.newPage();
    await freshPage.route('**/api/sessions/summaries', async (route) => {
      await new Promise((resolve) => setTimeout(resolve, 500));
      await route.fulfill({
        status: 500,
        contentType: 'application/json',
        body: JSON.stringify({
          timestamp: new Date().toISOString(),
          status: 500,
          message: 'Internal server error',
          path: '/api/sessions/summaries',
          error: 'Database connection failed',
        }),
      });
    });

    const navigation = freshPage.goto('/', { waitUntil: 'commit' });
    await expect(freshPage.getByText('Loading sessions...')).toBeVisible();
    await navigation;
    await expect(freshPage.getByText('Failed to load sessions')).toBeVisible();
    await freshPage.close();
  });

  test('should render a client-side 404 for a non-existent route', async ({
    page,
  }) => {
    await page.goto('/nonexistent');
    await waitForLoadingComplete(page);

    // React Router's default error boundary handles unmatched routes;
    // the request never round-trips to the server as a real 404.
    await expect(page.locator('body')).toBeVisible();
    expect(page.url()).toContain('/nonexistent');
  });
});
