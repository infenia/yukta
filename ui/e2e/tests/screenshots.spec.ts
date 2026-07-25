// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { test, expect } from '../fixtures';
import { waitForLoadingComplete } from '../utils';

// Screenshot comparison tolerances adjusted for environment-specific rendering differences
// (font rendering, antialiasing, DPI differences between local dev and CI ubuntu-latest)
// Ratio 0.01 (1% pixel difference) is imperceptible to humans; thresholds allow this
// while still catching major layout/color regressions (5%+ difference)
const screenshotOptions = {
  full: { maxDiffPixels: 2000, threshold: 0.2 },
  partial: { maxDiffPixels: 1000, threshold: 0.2 },
  strict: { maxDiffPixels: 500, threshold: 0.15 },
};

test.describe('Visual Regression - Screenshots', () => {
  test.describe('Session List Page', () => {
    test('should match screenshot - full page', async ({ page }) => {
      await page.goto('/');
      await waitForLoadingComplete(page);

      await expect(page).toHaveScreenshot('session-list-full-page.png', {
        ...screenshotOptions.full,
        animations: 'disabled',
      });
    });

    test('should match screenshot - table only', async ({ page }) => {
      await page.goto('/');
      await waitForLoadingComplete(page);

      const table = page.locator('table');
      await expect(table).toHaveScreenshot('session-list-table.png', {
        ...screenshotOptions.partial,
      });
    });

    test('should match screenshot - header', async ({ page }) => {
      await page.goto('/');
      await waitForLoadingComplete(page);

      const header = page.locator('header');
      await expect(header).toHaveScreenshot('session-list-header.png', {
        ...screenshotOptions.strict,
      });
    });
  });

  test.describe('Session List - Dark Mode', () => {
    test('should match screenshot - dark mode', async ({ page }) => {
      await page.goto('/');
      await waitForLoadingComplete(page);
      const toggle = page.getByRole('button', { name: 'Toggle theme' });
      for (let i = 0; i < 3; i += 1) {
        const title = await toggle.getAttribute('title');
        if (title === 'Current: dark') break;
        await toggle.click();
      }
      await expect(toggle).toHaveAttribute('title', 'Current: dark');
      await expect(page.locator('html')).toHaveClass(/dark/);
      // Extra wait for theme CSS to settle
      await page.waitForTimeout(500);

      await expect(page).toHaveScreenshot('session-list-dark-mode.png', {
        ...screenshotOptions.full,
        animations: 'disabled',
      });
    });

    test('should match screenshot - light mode', async ({ page }) => {
      await page.goto('/');
      await waitForLoadingComplete(page);

      await expect(page).toHaveScreenshot('session-list-light-mode.png', {
        ...screenshotOptions.full,
        animations: 'disabled',
      });
    });
  });

  test.describe('Session List - Responsive', () => {
    test('should match screenshot - mobile viewport', async ({ page }) => {
      await page.setViewportSize({ width: 375, height: 667 });
      await page.goto('/');
      await waitForLoadingComplete(page);

      await expect(page).toHaveScreenshot('session-list-mobile.png', {
        ...screenshotOptions.full,
        animations: 'disabled',
      });
    });

    test('should match screenshot - tablet viewport', async ({ page }) => {
      await page.setViewportSize({ width: 768, height: 1024 });
      await page.goto('/');
      await waitForLoadingComplete(page);

      await expect(page).toHaveScreenshot('session-list-tablet.png', {
        ...screenshotOptions.full,
        animations: 'disabled',
      });
    });

    test('should match screenshot - desktop viewport', async ({ page }) => {
      await page.setViewportSize({ width: 1920, height: 1080 });
      await page.goto('/');
      await waitForLoadingComplete(page);

      await expect(page).toHaveScreenshot('session-list-desktop.png', {
        ...screenshotOptions.full,
        animations: 'disabled',
      });
    });
  });

  test.describe('Error States - Screenshots', () => {
    test('should match screenshot - error state', async ({
      page,
      mockSessionsApi,
    }) => {
      await mockSessionsApi(page, { status: 500 });
      await page.goto('/');
      await expect(page.getByText('Failed to load sessions')).toBeVisible();

      await expect(page).toHaveScreenshot('session-list-error-state.png', {
        ...screenshotOptions.full,
        animations: 'disabled',
      });
    });

    test('should match screenshot - empty state', async ({
      page,
      mockSessionsApi,
    }) => {
      await mockSessionsApi(page, { sessions: [] });
      await page.goto('/');
      await waitForLoadingComplete(page);

      await expect(page).toHaveScreenshot('session-list-empty-state.png', {
        ...screenshotOptions.full,
        animations: 'disabled',
      });
    });
  });

  test.describe('UI Components - Screenshots', () => {
    test('should match screenshot - table row hover', async ({ page }) => {
      await page.goto('/');
      await waitForLoadingComplete(page);

      const firstRow = page.locator('tbody tr').first();
      await firstRow.hover();

      await expect(firstRow).toHaveScreenshot('session-list-row-hover.png', {
        ...screenshotOptions.partial,
      });
    });

    test('should match screenshot - filter bar', async ({ page }) => {
      await page.goto('/');
      await waitForLoadingComplete(page);

      const filterBar = page.getByPlaceholder(/Search by name or session ID/i).locator('..').locator('..');
      await expect(filterBar).toHaveScreenshot('session-list-filter-bar.png', {
        ...screenshotOptions.partial,
      });
    });

    test('should match screenshot - pagination footer', async ({ page }) => {
      await page.goto('/');
      await waitForLoadingComplete(page);

      const pagination = page.getByText(/items$/).locator('..');
      await expect(pagination).toHaveScreenshot('session-list-pagination.png', {
        ...screenshotOptions.partial,
      });
    });
  });
});
