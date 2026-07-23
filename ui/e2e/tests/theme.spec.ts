// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { test, expect } from '../fixtures';
import { waitForLoadingComplete } from '../utils';

const themeToggle = (page: import('@playwright/test').Page) =>
  page.getByRole('button', { name: 'Toggle theme' });

test.describe('Theme Switching', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/');
    await waitForLoadingComplete(page);
  });

  test('should start with system theme', async ({ page }) => {
    await expect(themeToggle(page)).toBeVisible();
    await expect(themeToggle(page)).toHaveAttribute('title', /Current: system/);
  });

  test('should switch between light and dark mode', async ({ page }) => {
    const toggle = themeToggle(page);
    const html = page.locator('html');

    await expect(toggle).toHaveAttribute('title', 'Current: system');

    await toggle.click();
    await expect(toggle).toHaveAttribute('title', 'Current: light');
    await expect(html).not.toHaveClass(/dark/);

    await toggle.click();
    await expect(toggle).toHaveAttribute('title', 'Current: dark');
    await expect(html).toHaveClass(/dark/);
  });

  test('should persist theme preference', async ({ page }) => {
    const toggle = themeToggle(page);

    await toggle.click();
    await expect(toggle).toHaveAttribute('title', 'Current: light');

    await page.reload();
    await waitForLoadingComplete(page);

    await expect(themeToggle(page)).toHaveAttribute('title', 'Current: light');
  });

  test('should have proper color contrast in both modes', async ({ page }) => {
    const table = page.locator('table');
    await expect(table).toBeVisible();

    // Force light mode explicitly; 'system' can resolve to either depending
    // on the host's OS preference.
    for (let i = 0; i < 3; i += 1) {
      const title = await themeToggle(page).getAttribute('title');
      if (title === 'Current: light') break;
      await themeToggle(page).click();
    }
    await expect(themeToggle(page)).toHaveAttribute('title', 'Current: light');
    await expect(page.locator('html')).not.toHaveClass(/dark/);

    const cell = table.locator('td').first();
    const lightColors = await cell.evaluate((el) => {
      const style = window.getComputedStyle(el);
      return { color: style.color, backgroundColor: style.backgroundColor };
    });
    expect(lightColors.color).toBeTruthy();

    await themeToggle(page).click();
    await expect(page.locator('html')).toHaveClass(/dark/);
    // Color changes are CSS-transitioned; wait for it to settle before reading.
    await expect(async () => {
      const color = await cell.evaluate((el) => window.getComputedStyle(el).color);
      expect(color).not.toBe(lightColors.color);
    }).toPass({ timeout: 2000 });

    const darkColors = await cell.evaluate((el) => {
      const style = window.getComputedStyle(el);
      return { color: style.color, backgroundColor: style.backgroundColor };
    });
    expect(darkColors.color).toBeTruthy();
    expect(darkColors).not.toEqual(lightColors);
  });

  test('should update all UI elements when theme changes', async ({ page }) => {
    const toggle = themeToggle(page);
    const html = page.locator('html');

    await expect(html).not.toHaveClass(/dark/);
    await toggle.click();
    await toggle.click();
    await expect(html).toHaveClass(/dark/);
  });

  test('should support prefers-color-scheme media query', async ({ page }) => {
    await page.evaluate(() => localStorage.removeItem('theme'));

    await page.emulateMedia({ colorScheme: 'dark' });
    await page.reload();
    await waitForLoadingComplete(page);
    await expect(page.locator('html')).toHaveClass(/dark/);

    await page.emulateMedia({ colorScheme: 'light' });
    await page.reload();
    await waitForLoadingComplete(page);
    await expect(page.locator('html')).not.toHaveClass(/dark/);
  });
});
