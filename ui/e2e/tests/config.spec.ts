// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { test, expect } from '../fixtures';
import { envelope } from '../api-envelope';

test.describe('Configuration page', () => {
  // the page asks the server for the current values; most tests do not care what it answers
  test.beforeEach(async ({ page }) => {
    await page.route('**/api/config?*', (route) =>
      route.fulfill(envelope('/config', []))
    );
  });

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

  test('shows what this server uses and filters to what is set', async ({
    page,
  }) => {
    await page.route('**/api/config?*', (route) => {
      const keys = new URL(route.request().url()).searchParams.getAll('key');
      return route.fulfill(
        envelope(
          '/config',
          keys.map((key) => ({
            key,
            value:
              key === 'server.port'
                ? '7000'
                : key === 'yukta.api-keys[0].key'
                  ? '******'
                  : null,
            source:
              key === 'server.port'
                ? 'argument'
                : key === 'yukta.api-keys[0].key'
                  ? 'environment variable'
                  : 'default',
          }))
        )
      );
    });
    await page.goto('/config');

    const port = page.getByRole('row', { name: /server\.port/ });
    await expect(port).toContainText('7000');
    await expect(port).toContainText('argument');
    await expect(
      page.getByRole('row', { name: /yukta\.api-keys\[0\]\.key/ })
    ).toContainText('******');

    await page.getByLabel('Value').selectOption('set');
    await expect(page.getByRole('row')).toHaveCount(3);
  });

  test('warns when the current values cannot be loaded', async ({ page }) => {
    await page.route('**/api/config?*', (route) =>
      route.fulfill({ status: 403, body: '{}' })
    );
    await page.goto('/config');

    await expect(
      page.getByText('Could not load the current values.')
    ).toBeVisible();
    await expect(
      page.getByRole('button', { name: 'server.port' })
    ).toBeVisible();
  });
});
