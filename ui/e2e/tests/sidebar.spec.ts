// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { test, expect } from '../fixtures';
import { envelope } from '../api-envelope';

const SECTIONS = [
  { label: 'Sessions', path: '/sessions' },
  { label: 'Runs', path: '/runs' },
  { label: 'Plugins', path: '/plugins' },
  { label: 'Supervisors', path: '/supervisors' },
  { label: 'Control', path: '/control' },
  { label: 'Config', path: '/config' },
];

test.describe('Sidebar (desktop rail)', () => {
  test('lists the six sections in order', async ({ page }) => {
    await page.goto('/sessions');

    const links = page
      .getByRole('navigation', { name: 'Primary' })
      .first()
      .getByRole('link');
    await expect(links).toHaveCount(6);
    // icon-only: the label is the accessible name, shown in a tooltip
    expect(
      await links.evaluateAll((els) =>
        els.map((el) => el.getAttribute('aria-label'))
      )
    ).toEqual(SECTIONS.map((s) => s.label));
  });

  for (const { label, path } of SECTIONS) {
    test(`${label} opens ${path} and is marked current`, async ({ page }) => {
      await page.route('**/api/config?*', (route) =>
        route.fulfill(envelope('/config', []))
      );
      await page.goto('/sessions');
      const rail = page.getByRole('navigation', { name: 'Primary' }).first();

      await rail.getByRole('link', { name: label }).click();

      await expect(page).toHaveURL(new RegExp(`${path}$`));
      await expect(rail.getByRole('link', { name: label })).toHaveAttribute(
        'aria-current',
        'page'
      );
      await expect(rail.locator('[aria-current="page"]')).toHaveCount(1);
    });
  }

  test('Sessions stays current on a page under /sessions', async ({ page }) => {
    await page.route('**/api/templates*', (route) =>
      route.fulfill(envelope('/api/templates', []))
    );
    await page.goto('/sessions/new');

    await expect(
      page
        .getByRole('navigation', { name: 'Primary' })
        .first()
        .getByRole('link', { name: 'Sessions' })
    ).toHaveAttribute('aria-current', 'page');
  });

  test('the label shows on hover and on keyboard focus', async ({ page }) => {
    await page.goto('/sessions');
    const link = page
      .getByRole('navigation', { name: 'Primary' })
      .first()
      .getByRole('link', { name: 'Runs' });
    const tip = page.locator('[data-slot="tooltip-content"]', {
      hasText: 'Runs',
    });
    await expect(tip).toHaveCount(0);

    await link.hover();
    await expect(tip).toBeVisible();

    await page.mouse.move(600, 400);
    await expect(tip).toHaveCount(0);
    await page.keyboard.press('Tab');
    await link.focus();
    await page.keyboard.press('Tab');
    await page.keyboard.press('Shift+Tab');
    await expect(tip).toBeVisible();
  });

  test('is replaced by the bottom bar on a phone', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 800 });
    await page.goto('/sessions');

    const bar = page.getByRole('navigation', { name: 'Primary' });
    await expect(bar.filter({ visible: true })).toHaveCount(1);
    const links = bar.filter({ visible: true }).getByRole('link');
    await expect(links).toHaveText(
      SECTIONS.map((s) => new RegExp(`${s.label}$`))
    );

    await links.filter({ hasText: 'Plugins' }).click();
    await expect(page).toHaveURL(/\/plugins$/);
    await expect(links.filter({ hasText: 'Plugins' })).toHaveAttribute(
      'aria-current',
      'page'
    );
  });

  test('is replaced by nothing wider than the rail on desktop', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 1280, height: 800 });
    await page.goto('/sessions');

    const rail = page
      .getByRole('navigation', { name: 'Primary' })
      .filter({ visible: true });
    await expect(rail).toHaveCount(1);
    const box = await rail.boundingBox();
    expect(box?.width).toBe(64);
  });
});
