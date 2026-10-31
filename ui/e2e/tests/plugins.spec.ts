// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { Page } from '@playwright/test';
import { test, expect } from '../fixtures';

const details: Record<string, object> = {
  OneShotTrigger: {
    type: 'OneShotTrigger',
    category: 'TRIGGER',
    description: 'Fires once when the workflow starts',
    usagePattern: 'trigger: one-shot',
    uiDesign: null,
    outputPorts: [],
  },
  ShellProcessor: {
    type: 'ShellProcessor',
    category: 'PROCESSOR',
    description: 'Runs a shell command',
    usagePattern: 'command: echo hi',
    uiDesign: null,
    outputPorts: ['success', 'failure'],
  },
  FileSink: {
    type: 'FileSink',
    category: 'TERMINAL',
    description: 'Writes output to a file',
    usagePattern: null,
    uiDesign: null,
    outputPorts: [],
  },
};

const envelope = (path: string, data: unknown) => ({
  status: 200,
  contentType: 'application/json',
  body: JSON.stringify({
    timestamp: new Date().toISOString(),
    status: 200,
    message: 'OK',
    path,
    data,
  }),
});

async function mockPluginsApi(page: Page) {
  await page.route('**/api/plugins', (route) =>
    route.fulfill(
      envelope(
        '/api/plugins',
        Object.values(details).map((d) => ({
          type: (d as { type: string }).type,
          category: (d as { category: string }).category,
        }))
      )
    )
  );
  await page.route('**/api/plugins/*', (route) => {
    const type = decodeURIComponent(
      new URL(route.request().url()).pathname.split('/').pop() ?? ''
    );
    return route.fulfill(envelope(`/api/plugins/${type}`, details[type]));
  });
}

test.describe('Plugins page', () => {
  test('is reachable from the header and shows the first plugin with a canvas preview', async ({
    page,
  }) => {
    await mockPluginsApi(page);
    await page.goto('/sessions');
    await page.getByRole('link', { name: 'Plugins' }).click();

    await expect(page).toHaveURL(/\/plugins$/);
    await expect(
      page.getByRole('heading', { name: 'OneShotTrigger', level: 2 })
    ).toBeVisible();
    await expect(
      page.getByText('Fires once when the workflow starts')
    ).toBeVisible();
    await expect(
      page.getByTestId('workflow-node-OneShotTrigger')
    ).toBeVisible();
  });

  test('groups plugins by category', async ({ page }) => {
    await mockPluginsApi(page);
    await page.goto('/plugins');

    const nav = page.getByRole('navigation', { name: 'Plugins' });
    await expect(nav.getByRole('heading', { name: 'Triggers' })).toBeVisible();
    await expect(
      nav.getByRole('heading', { name: 'Processors' })
    ).toBeVisible();
    await expect(nav.getByRole('heading', { name: 'Terminals' })).toBeVisible();
  });

  test('selecting a plugin updates details, URL and preview', async ({
    page,
  }) => {
    await mockPluginsApi(page);
    await page.goto('/plugins');

    await page.getByRole('button', { name: 'ShellProcessor' }).click();

    await expect(page).toHaveURL(/\?type=ShellProcessor$/);
    await expect(page.getByText('Runs a shell command')).toBeVisible();
    await expect(page.getByText('success, failure')).toBeVisible();
    await page.getByRole('tab', { name: 'Example' }).click();
    await expect(page.getByText('command: echo hi')).toBeVisible();
    await expect(
      page.getByTestId('workflow-node-ShellProcessor')
    ).toBeVisible();
  });

  test('deep link selects the plugin and terminal shows no output ports', async ({
    page,
  }) => {
    await mockPluginsApi(page);
    await page.goto('/plugins?type=FileSink');

    await expect(
      page.getByRole('heading', { name: 'FileSink', level: 2 })
    ).toBeVisible();
    await expect(page.getByText('None (terminal)')).toBeVisible();
    await expect(page.getByTestId('workflow-node-FileSink')).toBeVisible();
  });

  test('filter narrows the list', async ({ page }) => {
    await mockPluginsApi(page);
    await page.goto('/plugins');

    await page.getByLabel('Filter plugins').fill('shell');

    await expect(
      page.getByRole('button', { name: 'ShellProcessor' })
    ).toBeVisible();
    await expect(page.getByRole('button', { name: 'FileSink' })).toHaveCount(0);
  });

  test('shows an error when the plugin list fails', async ({ page }) => {
    await page.route('**/api/plugins', (route) =>
      route.fulfill({
        status: 500,
        contentType: 'application/json',
        body: '{"message":"boom"}',
      })
    );
    await page.goto('/plugins');

    // toContainText: the alert also holds its (aria-hidden) icon glyph name
    await expect(page.getByRole('alert')).toContainText(
      'Failed to load plugins.'
    );
  });
});
