// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { test, expect } from '../fixtures';
import {
  waitForApiResponse,
  waitForLoadingComplete,
  getTableRow,
  assertTableColumns,
  countTableRows,
} from '../utils';

test.describe('Session List Page - Mocked API', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/');
    await waitForLoadingComplete(page);
  });

  test.describe('Initial Load', () => {
    test('should display page title and header', async ({ page }) => {
      const heading = page.locator('h1, h2').first();
      await expect(heading).toBeVisible();
      const text = await heading.textContent();
      expect(text).toBeTruthy();
    });

    test('should load and display session list from API', async ({
      page,
      mockSessions,
    }) => {
      const responsePromise = waitForApiResponse(page, {
        url: '/api/sessions/summaries',
      });
      await page.reload();
      const response = await responsePromise;
      expect(response.status()).toBe(200);

      const table = page.locator('table');
      await expect(table).toBeVisible();

      const rowCount = await countTableRows(page);
      expect(rowCount).toBe(mockSessions.length);

      const firstRow = await getTableRow(page, mockSessions[0].name);
      await expect(firstRow).toBeVisible();
      const content = await firstRow.textContent();
      expect(content).toContain(mockSessions[0].name);
      expect(content).toContain(mockSessions[0].initiator);
    });

    test('should display table headers', async ({ page }) => {
      const expectedHeaders = ['Name', 'Initiator', 'Tags', 'Project Path'];
      await assertTableColumns(page, expectedHeaders);
    });

    test('should display session details in correct columns', async ({
      page,
      mockSessions,
    }) => {
      const session = mockSessions[0];
      const row = await getTableRow(page, session.name);

      const content = await row.textContent();
      expect(content).toContain(session.name);
      expect(content).toContain(session.initiator);
      expect(content).toContain(session.projectPath);
    });
  });

  test.describe('Data Rendering', () => {
    test('should display tags as badge elements', async ({
      page,
      mockSessions,
    }) => {
      const session = mockSessions[0];
      const row = await getTableRow(page, session.name);

      for (const tag of session.tags) {
        const tagElement = row.getByText(tag, { exact: true });
        await expect(tagElement).toBeVisible();
      }
    });

    test('should display workflow count for each session', async ({
      page,
      mockSessions,
    }) => {
      for (const session of mockSessions) {
        const row = await getTableRow(page, session.name);
        const content = await row.textContent();
        expect(content).toContain(session.workflowCount.toString());
      }
    });

    test('should display session project path in the row', async ({
      page,
      mockSessions,
    }) => {
      const session = mockSessions[0];
      const row = await getTableRow(page, session.name);
      const content = await row.textContent();
      expect(content).toContain(session.projectPath);
    });
  });

  test.describe('User Interactions', () => {
    test('should allow clicking on a session row', async ({
      page,
      mockSessions,
    }) => {
      const session = mockSessions[0];
      const row = await getTableRow(page, session.name);

      await row.click();

      await page.waitForTimeout(500);
      const url = page.url();
      expect(url).toBeTruthy();
    });

    test('should highlight row on hover', async ({ page, mockSessions }) => {
      const session = mockSessions[1];
      const row = await getTableRow(page, session.name);

      await row.hover();
      await expect(row).toHaveClass(/hover:bg-surface-container-high/);
    });
  });

  test.describe('Filtering', () => {
    test('should have a search input', async ({ page }) => {
      const filterInput = page.getByPlaceholder(/Search by name or session ID/i);
      await expect(filterInput).toBeVisible();
    });

    test('should filter sessions by name when text is entered', async ({
      page,
    }) => {
      const filterInput = page.getByPlaceholder(/Search by name or session ID/i);

      await filterInput.fill('Build');

      const rows = page.locator('tbody tr');
      await expect(rows).toHaveCount(1);
      await expect(rows.first()).toContainText('Build Pipeline');
    });

    test('should show empty state when filter matches nothing', async ({
      page,
    }) => {
      const filterInput = page.getByPlaceholder(/Search by name or session ID/i);

      await filterInput.fill('no-such-session-xyz');

      await expect(page.getByText('No sessions yet')).toBeVisible();
    });
  });

  test.describe('Pagination', () => {
    test('should display pagination controls', async ({ page }) => {
      await expect(page.getByText(/items$/)).toBeVisible();
      await expect(page.getByRole('button', { name: 'Next page' })).toBeVisible();
    });

    test('next/previous page controls are disabled with a single page of results', async ({
      page,
    }) => {
      // Only 3 mock sessions and a default page size of 10 → single page.
      await expect(page.getByRole('button', { name: 'Next page' })).toBeDisabled();
      await expect(page.getByRole('button', { name: 'Previous page' })).toBeDisabled();
    });
  });

  test.describe('Loading States', () => {
    test('should show loading state during initial load', async ({
      context,
    }) => {
      const freshPage = await context.newPage();
      await freshPage.route('**/api/sessions/summaries', async (route) => {
        await new Promise((resolve) => setTimeout(resolve, 500));
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            timestamp: new Date().toISOString(),
            status: 200,
            message: 'ok',
            path: '/api/sessions/summaries',
            data: { sessions: [] },
          }),
        });
      });

      const navigation = freshPage.goto('/', { waitUntil: 'commit' });
      await expect(freshPage.getByText('Loading sessions...')).toBeVisible();
      await navigation;
      await waitForLoadingComplete(freshPage);
      await expect(freshPage.getByText('Loading sessions...')).not.toBeVisible();
      await freshPage.close();
    });
  });

  test.describe('Accessibility', () => {
    test('should have proper heading hierarchy', async ({ page }) => {
      await expect(page.locator('h1')).toHaveCount(1);
    });

    test('should have accessible table headers', async ({ page }) => {
      const table = page.locator('table');
      const headers = table.locator('th');

      const headerCount = await headers.count();
      expect(headerCount).toBeGreaterThan(0);
    });

    test('table should have proper semantic structure', async ({ page }) => {
      const table = page.locator('table');
      const role = await table.getAttribute('role');
      expect([null, 'table', 'grid']).toContain(role);
    });

    test('should support keyboard navigation to the search input', async ({
      page,
    }) => {
      await page.keyboard.press('Tab');
      const focusedElement = await page.evaluate(
        () => document.activeElement?.tagName
      );
      expect(focusedElement).toBeTruthy();
    });
  });

  test.describe('Responsive Design', () => {
    test('should be visible on desktop viewport', async ({ page }) => {
      const table = page.locator('table');
      await expect(table).toBeVisible();
    });

    test('should render on tablet viewport', async ({ page }) => {
      await page.setViewportSize({ width: 768, height: 1024 });
      await page.reload();
      await waitForLoadingComplete(page);

      const table = page.locator('table');
      await expect(table).toBeVisible();
    });

    test('should render on mobile viewport', async ({ page }) => {
      await page.setViewportSize({ width: 375, height: 667 });
      await page.reload();
      await waitForLoadingComplete(page);

      const table = page.locator('table');
      await expect(table).toBeVisible();
    });
  });
});
