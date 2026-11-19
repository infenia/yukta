// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { test, expect } from '../fixtures';
import { envelope } from '../api-envelope';

test.describe('Configuration page', () => {
  test('is reachable from the header and lists the options', async ({
    page,
  }) => {
    await page.goto('/sessions');
    await page.getByRole('link', { name: 'Config' }).click();

    await expect(page).toHaveURL(/\/config$/);
    await expect(
      page.getByRole('heading', { name: 'Configuration', level: 1 })
    ).toBeVisible();
    await expect(page.getByText('How configuration works')).toBeVisible();
    await expect(
      page.getByRole('button', { name: 'server.port' })
    ).toBeVisible();
  });

  test('search narrows the list and a row shows how to set the option', async ({
    page,
  }) => {
    await page.goto('/config');
    await page.getByLabel('Search options').fill('YUKTA_PLUGINS_ENABLED');

    const row = page.getByRole('button', { name: 'yukta.plugins.enabled' });
    await expect(row).toBeVisible();
    await expect(page.getByRole('button', { name: 'server.port' })).toHaveCount(
      0
    );

    await row.click();
    await expect(
      page.getByText('Environment variable', { exact: true })
    ).toBeVisible();
    await expect(
      page.getByText('docker run -e YUKTA_PLUGINS_ENABLED=<value>')
    ).toBeVisible();
  });

  test('the native filter shows the build-time caveat', async ({ page }) => {
    await page.goto('/config');
    await page.getByLabel('Mode').selectOption('native');
    await page
      .getByRole('button', { name: 'yukta.session.store-type' })
      .click();
    await expect(page.getByText('Native image and Docker')).toBeVisible();
  });

  test('the Runtime tab shows the live limits and says they are not saved', async ({
    page,
  }) => {
    await page.route('**/api/scheduler/limits*', (route) =>
      route.fulfill(
        envelope('/limits', {
          maxConcurrent: 4,
          maxPerWorkflow: 0,
          running: 2,
          queued: 1,
          queueCapacity: 100,
        })
      )
    );
    await page.route('**/api/admin/drain*', (route) =>
      route.fulfill(envelope('/drain', { draining: false }))
    );
    await page.goto('/config');
    await page.getByRole('tab', { name: 'Runtime' }).click();

    await expect(page).toHaveURL(/\/config\?tab=runtime$/);
    await expect(
      page.getByText('Changes here are live, but not saved')
    ).toBeVisible();
    await expect(page.getByText('2 of 4')).toBeVisible();
  });

  test('the Packaging tab compares JVM, native and Docker', async ({
    page,
  }) => {
    await page.goto('/config');
    await page.getByRole('tab', { name: 'Packaging' }).click();

    await expect(page).toHaveURL(/\/config\?tab=packaging$/);
    await expect(
      page.getByRole('heading', {
        name: 'JVM jar, native binary and Docker image',
      })
    ).toBeVisible();
    await expect(
      page.getByRole('row', { name: /JAVA_TOOL_OPTIONS/ })
    ).toContainText('Ignored.');
    await expect(
      page.getByRole('heading', { name: 'Running the Docker image' })
    ).toBeVisible();
  });
});
