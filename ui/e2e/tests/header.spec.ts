// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { test, expect } from '../fixtures';
import { envelope } from '../api-envelope';

const me = (role: string) =>
  envelope('/api/me', {
    authenticated: true,
    keyName: 'dev',
    role,
    sessions: null,
    authEnabled: true,
    permissions: {
      startRuns: true,
      controlRuns: true,
      editSessions: true,
      deleteSessions: true,
      changeLimits: true,
      sendCommands: true,
    },
  });

test.describe('Header', () => {
  test.beforeEach(async ({ page }) => {
    await page.route('**/api/me', (route) => route.fulfill(me('ADMIN')));
    await page.goto('/sessions');
  });

  test('shows the logo, search, docs link, bell, account and theme toggle', async ({
    page,
  }) => {
    const header = page.getByRole('banner');
    await expect(header.getByRole('link', { name: 'Yukta' })).toBeVisible();
    await expect(
      header.getByRole('button', { name: /Search and jump to/ })
    ).toBeVisible();
    await expect(
      header.getByText('Jump to a session, workflow or run…')
    ).toBeVisible();
    await expect(
      header.getByRole('button', { name: /^Notifications/ })
    ).toBeVisible();
    await expect(
      header.getByRole('button', { name: 'Toggle theme' })
    ).toBeVisible();
    await expect(header.getByTestId('brand-line')).toBeAttached();
  });

  test('the logo goes home to the session list', async ({ page }) => {
    await page.goto('/runs');
    await page.getByRole('banner').getByRole('link', { name: 'Yukta' }).click();

    await expect(page).toHaveURL(/\/sessions$/);
  });

  test('the docs link opens the documentation in a new tab', async ({
    page,
  }) => {
    const docs = page.getByRole('link', { name: /^Documentation/ });
    await expect(docs).toHaveAttribute('href', 'https://yukta.infenia.com/');
    await expect(docs).toHaveAttribute('target', '_blank');
    await expect(docs).toHaveAttribute('rel', /noreferrer/);
  });

  test('the search button and Ctrl+K open the command palette, Escape closes it', async ({
    page,
  }) => {
    const palette = page.getByRole('dialog', { name: 'Command palette' });
    await page.getByRole('button', { name: /Search and jump to/ }).click();
    await expect(palette).toBeVisible();
    await expect(palette.getByRole('combobox')).toBeFocused();

    await page.keyboard.press('Escape');
    await expect(palette).toBeHidden();

    await page.keyboard.press('Control+k');
    await expect(palette).toBeVisible();
  });

  test('the bell opens the notifications panel', async ({ page }) => {
    await page.getByRole('button', { name: /^Notifications/ }).click();

    await expect(page.getByRole('dialog').first()).toBeVisible();
  });

  test('the account chip shows the key name and role, and offers to change the key or sign out', async ({
    page,
  }) => {
    const chip = page.getByRole('button', { name: 'Account', exact: true });
    await expect(chip).toContainText('dev');
    await expect(chip).toContainText('ADMIN');

    await chip.click();
    await page.getByRole('menuitem', { name: 'Change API key' }).click();
    const gate = page.getByRole('dialog', { name: 'Enter your API key' });
    await expect(gate).toBeVisible();
    // the key in use still works, so the gate can be closed without a new one
    await gate.getByRole('button', { name: 'Keep the current key' }).click();
    await expect(gate).toHaveCount(0);
    await expect(chip).toBeVisible();
  });

  test('Sign out forgets the key and reloads', async ({ page }) => {
    await page.evaluate(() => sessionStorage.setItem('yukta.apiKey', 'k'));
    await page.getByRole('button', { name: 'Account', exact: true }).click();
    await page.getByRole('menuitem', { name: 'Sign out' }).click();

    await page.waitForLoadState('load');
    expect(
      await page.evaluate(() => sessionStorage.getItem('yukta.apiKey'))
    ).toBeNull();
  });

  test('the account chip is hidden when the server needs no key', async ({
    page,
  }) => {
    await page.route('**/api/me', (route) =>
      route.fulfill(
        envelope('/api/me', {
          authenticated: false,
          keyName: null,
          role: 'ADMIN',
          sessions: null,
          authEnabled: false,
          permissions: {},
        })
      )
    );
    await page.reload();

    await expect(
      page.getByRole('button', { name: 'Toggle theme' })
    ).toBeVisible();
    await expect(
      page.getByRole('button', { name: 'Account', exact: true })
    ).toHaveCount(0);
  });

  test('on a phone the toggles and account collapse into one account menu', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 390, height: 800 });

    await expect(
      page.getByRole('button', { name: 'Toggle theme' })
    ).toBeHidden();
    await page.getByRole('button', { name: 'Account and theme' }).click();
    await expect(page.getByText('dev · ADMIN')).toBeVisible();
    await page.getByRole('menuitemradio', { name: 'Dark' }).click();

    await expect(page.locator('html')).toHaveClass(/dark/);
  });
});
