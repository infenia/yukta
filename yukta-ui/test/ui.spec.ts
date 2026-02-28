import { test, expect } from '@playwright/test';

test.describe('Yukta UI E2E Tests', () => {
  test('Sessions page should load and match screenshot', async ({ page }) => {
    await page.goto('/ui');
    await expect(page).toHaveTitle(/Dashboard - Yukta/);
    await expect(page.getByRole('heading', { name: 'Active Sessions' })).toBeVisible();
    // Wait for any potential animations
    await page.waitForTimeout(1000);
    await expect(page).toHaveScreenshot('sessions-page.png');
  });

  test('Session details page should load and match screenshot', async ({ page }) => {
    await page.goto('/ui/sessions/test-session');
    await expect(page.locator('h2')).toBeVisible();
    await page.waitForTimeout(1000);
    await expect(page).toHaveScreenshot('session-details.png');
  });

  test('Workflow page should display DAG diagram and match screenshot', async ({ page }) => {
    await page.goto('/ui/sessions/test-session/workflow/quality-check');

    const dagContainer = page.locator('#dag-container');
    await expect(dagContainer).toBeVisible();

    // Check if main SVG is present
    const svg = page.locator('svg[x-ref="dagSvg"]');
    await expect(svg).toBeVisible();

    // Wait for ELK layout and rendering
    await page.waitForTimeout(2000);

    await expect(page).toHaveScreenshot('workflow-dag.png');
  });
});
