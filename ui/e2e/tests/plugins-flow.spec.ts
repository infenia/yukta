// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { Page } from '@playwright/test';
import { test, expect } from '../fixtures';
import { envelope } from '../api-envelope';

interface Detail {
  type: string;
  category: 'TRIGGER' | 'PROCESSOR' | 'TERMINAL';
  tier?: string;
  restricted?: boolean;
  description: string | null;
  usagePattern: string | null;
  uiDesign: null;
  outputPorts: string[];
  configSchema?: object[];
}

const field = (over: object) => ({
  name: 'f',
  type: 'STRING',
  required: false,
  defaultValue: null,
  options: [],
  description: '',
  ...over,
});

const PLUGINS: Record<string, Detail> = {
  CONSTANT_SOURCE: {
    type: 'CONSTANT_SOURCE',
    category: 'TRIGGER',
    tier: 'CORE',
    description:
      'Fires once with a fixed payload.\nSecond line of the description.',
    usagePattern: null,
    uiDesign: null,
    outputPorts: [],
  },
  PROCESS_EXECUTOR: {
    type: 'PROCESS_EXECUTOR',
    category: 'PROCESSOR',
    tier: 'EXTENDED',
    restricted: true,
    description: 'Runs a command.',
    usagePattern: 'command: [echo, hi]',
    uiDesign: null,
    outputPorts: ['success', 'failure'],
    configSchema: [
      field({
        name: 'command',
        type: 'LIST',
        required: true,
        description: 'The command and its arguments',
      }),
      field({
        name: 'outputFormat',
        type: 'ENUM',
        options: ['RAW', 'JSON'],
        defaultValue: 'RAW',
        description: 'How the output is read',
      }),
      field({ name: 'env', type: 'MAP', defaultValue: { A: '1' } }),
    ],
  },
  JSON_TRANSFORM: {
    type: 'JSON_TRANSFORM',
    category: 'PROCESSOR',
    description: 'Reshapes JSON.',
    usagePattern: null,
    uiDesign: null,
    outputPorts: [],
  },
  CONSOLE_TERMINAL: {
    type: 'CONSOLE_TERMINAL',
    category: 'TERMINAL',
    description: null,
    usagePattern: null,
    uiDesign: null,
    outputPorts: [],
  },
};

interface Options {
  plugins?: Record<string, Detail>;
  usage?: Record<
    string,
    { sessionId: string; workflowId: string; nodes: number }[]
  >;
  usageStatus?: number;
}

async function mockPlugins(
  page: Page,
  { plugins = PLUGINS, usage = {}, usageStatus = 200 }: Options = {}
) {
  await page.route('**/api/plugins', (route) =>
    route.fulfill(
      envelope(
        '/plugins',
        Object.values(plugins).map(({ type, category, tier, restricted }) => ({
          type,
          category,
          tier,
          restricted,
        }))
      )
    )
  );
  await page.route(/\/api\/plugins\/[^/]+\/usage$/, (route) => {
    const type = decodeURIComponent(
      new URL(route.request().url()).pathname.split('/').slice(-2)[0]
    );
    return usageStatus === 200
      ? route.fulfill(envelope('/usage', usage[type] ?? []))
      : route.fulfill({
          status: usageStatus,
          contentType: 'application/json',
          body: '{"message":"no usage"}',
        });
  });
  await page.route(/\/api\/plugins\/[^/]+$/, (route) => {
    const type = decodeURIComponent(
      new URL(route.request().url()).pathname.split('/').pop() ?? ''
    );
    return plugins[type]
      ? route.fulfill(envelope(`/plugins/${type}`, plugins[type]))
      : route.fulfill({
          status: 404,
          contentType: 'application/json',
          body: '{"message":"Unknown plugin"}',
        });
  });
}

const list = (page: Page) => page.getByRole('navigation', { name: 'Plugins' });
const item = (page: Page, type: string) =>
  list(page).getByRole('button', { name: new RegExp(type) });

test.describe('Plugins - the list', () => {
  test.beforeEach(async ({ page }) => {
    await mockPlugins(page);
  });

  test('has a title, a description and a filter', async ({ page }) => {
    await page.goto('/plugins');

    await expect(
      page.getByRole('heading', { level: 1, name: 'Plugins' })
    ).toBeVisible();
    await expect(
      page.getByText('Building blocks you can place on a workflow canvas.')
    ).toBeVisible();
    await expect(page.getByLabel('Filter plugins')).toHaveAttribute(
      'placeholder',
      'Filter by name or ID'
    );
  });

  test('groups the plugins by category with their counts, showing a label and the ID', async ({
    page,
  }) => {
    await page.goto('/plugins');

    for (const [heading, count] of [
      ['Triggers', '1'],
      ['Processors', '2'],
      ['Terminals', '1'],
    ]) {
      await expect(
        list(page).getByRole('heading', {
          name: new RegExp(`${heading}\\s*${count}`),
        })
      ).toBeVisible();
    }
    const processor = item(page, 'PROCESS_EXECUTOR');
    await expect(processor).toContainText('Process Executor');
    await expect(processor).toContainText('PROCESS_EXECUTOR');
  });

  test('the first plugin is selected until another is chosen', async ({
    page,
  }) => {
    await page.goto('/plugins');

    await expect(item(page, 'CONSTANT_SOURCE')).toHaveAttribute(
      'aria-current',
      'true'
    );
    await expect(item(page, 'PROCESS_EXECUTOR')).toHaveAttribute(
      'aria-current',
      'false'
    );
    await expect(
      page.getByRole('heading', { level: 2, name: 'Constant Source' })
    ).toBeVisible();

    await item(page, 'PROCESS_EXECUTOR').click();

    await expect(page).toHaveURL(/\/plugins\?type=PROCESS_EXECUTOR$/);
    await expect(item(page, 'PROCESS_EXECUTOR')).toHaveAttribute(
      'aria-current',
      'true'
    );
    await expect(
      page.getByRole('heading', { level: 2, name: 'Process Executor' })
    ).toBeVisible();
  });

  test('the filter matches the label and the ID without regard to case', async ({
    page,
  }) => {
    await page.goto('/plugins');
    const filter = page.getByLabel('Filter plugins');

    await filter.fill('process exec');
    await expect(item(page, 'PROCESS_EXECUTOR')).toBeVisible();
    await expect(item(page, 'CONSTANT_SOURCE')).toHaveCount(0);

    await filter.fill('json_trans');
    await expect(item(page, 'JSON_TRANSFORM')).toBeVisible();
    await expect(item(page, 'PROCESS_EXECUTOR')).toHaveCount(0);
    // an empty category disappears with its heading
    await expect(
      list(page).getByRole('heading', { name: /Triggers/ })
    ).toHaveCount(0);

    await filter.fill('');
    await expect(list(page).getByRole('button')).toHaveCount(4);
  });

  test('a filter that matches nothing leaves no groups', async ({ page }) => {
    await page.goto('/plugins');

    await page.getByLabel('Filter plugins').fill('zzz-nothing');

    await expect(list(page).getByRole('heading')).toHaveCount(0);
    await expect(list(page).getByRole('button')).toHaveCount(0);
  });

  test('the arrow keys move between the plugins', async ({ page }) => {
    await page.goto('/plugins');
    await item(page, 'CONSTANT_SOURCE').focus();

    await page.keyboard.press('ArrowDown');
    await expect(item(page, 'PROCESS_EXECUTOR')).toBeFocused();
    await page.keyboard.press('ArrowDown');
    await expect(item(page, 'JSON_TRANSFORM')).toBeFocused();
    await page.keyboard.press('ArrowUp');
    await expect(item(page, 'PROCESS_EXECUTOR')).toBeFocused();
  });

  test('a link to a plugin selects it', async ({ page }) => {
    await page.goto('/plugins?type=CONSOLE_TERMINAL');

    await expect(item(page, 'CONSOLE_TERMINAL')).toHaveAttribute(
      'aria-current',
      'true'
    );
    await expect(
      page.getByRole('heading', { level: 2, name: 'Console Terminal' })
    ).toBeVisible();
  });
});

test.describe('Plugins - states', () => {
  test('shows a loading state while the list loads', async ({ page }) => {
    await mockPlugins(page);
    await page.route('**/api/plugins', async (route) => {
      await new Promise((resolve) => setTimeout(resolve, 800));
      return route.fallback();
    });
    await page.goto('/plugins');

    await expect(page.getByText('Loading plugins…')).toBeVisible();
    await expect(item(page, 'CONSTANT_SOURCE')).toBeVisible();
  });

  test('says so when the server has no plugins', async ({ page }) => {
    await mockPlugins(page, { plugins: {} });
    await page.goto('/plugins');

    await expect(page.getByText('No plugins available.')).toBeVisible();
  });

  test('a plugin that cannot be loaded says which one', async ({ page }) => {
    await mockPlugins(page);
    await page.goto('/plugins?type=GONE_PLUGIN');

    await expect(page.getByText('Failed to load GONE_PLUGIN.')).toBeVisible();
  });
});

test.describe('Plugins - the details', () => {
  test.beforeEach(async ({ page }) => {
    await mockPlugins(page, {
      usage: {
        PROCESS_EXECUTOR: [
          { sessionId: 'build', workflowId: 'check', nodes: 3 },
          { sessionId: 'deploy', workflowId: 'ship', nodes: 1 },
        ],
      },
    });
  });

  test('shows the label, category, tier, restriction, ID and a multi-line description', async ({
    page,
  }) => {
    await page.goto('/plugins?type=PROCESS_EXECUTOR');

    const article = page.getByRole('article');
    await expect(
      article.getByRole('heading', { name: 'Process Executor' })
    ).toBeVisible();
    await expect(article).toContainText('Processor');
    await expect(article).toContainText('Extended');
    await expect(article.getByText('Restricted')).toHaveAttribute(
      'title',
      'Reaches outside the engine; an operator opts in'
    );
    await expect(
      article.locator('code', { hasText: 'PROCESS_EXECUTOR' })
    ).toBeVisible();
    await expect(article.getByText('Runs a command.')).toBeVisible();

    await page.goto('/plugins?type=CONSTANT_SOURCE');
    await expect(
      page.getByText(/Second line of the description\./)
    ).toBeVisible();
    await expect(page.getByText('Restricted')).toHaveCount(0);
  });

  test('previews the plugin as a node on the canvas', async ({ page }) => {
    await page.goto('/plugins?type=PROCESS_EXECUTOR');

    await expect(
      page
        .getByRole('region', { name: 'On the canvas' })
        .getByTestId('workflow-node-PROCESS_EXECUTOR')
    ).toBeVisible();
  });

  test('a plugin with a schema opens on Configuration and lists every field', async ({
    page,
  }) => {
    await page.goto('/plugins?type=PROCESS_EXECUTOR');

    const tabs = page.getByRole('tablist', { name: 'Plugin details' });
    await expect(
      tabs.getByRole('tab', { name: /Configuration/ })
    ).toHaveAttribute('aria-selected', 'true');
    await expect(
      tabs.getByRole('tab', { name: /Configuration/ })
    ).toContainText('3');
    const fields = page
      .getByRole('listitem')
      .filter({ has: page.locator('code') });
    const command = fields.filter({ hasText: 'The command and its arguments' });
    await expect(command).toContainText('command');
    await expect(command).toContainText('LIST');
    await expect(command).toContainText('required');
    await expect(command).toContainText('default —');
    const format = fields.filter({ hasText: 'How the output is read' });
    await expect(format).toContainText('ENUM');
    await expect(format).toContainText('default RAW');
    await expect(format).not.toContainText('required');
    await expect(fields.filter({ hasText: 'env' }).first()).toContainText(
      '{"A":"1"}'
    );
  });

  test('a plugin without a schema opens on Ports and has no Configuration tab', async ({
    page,
  }) => {
    await page.goto('/plugins?type=JSON_TRANSFORM');

    const tabs = page.getByRole('tablist', { name: 'Plugin details' });
    await expect(tabs.getByRole('tab', { name: /Configuration/ })).toHaveCount(
      0
    );
    await expect(tabs.getByRole('tab', { name: /Ports/ })).toHaveAttribute(
      'aria-selected',
      'true'
    );
  });

  test('Ports explains ports and lists them, or the default one, or none for a terminal', async ({
    page,
  }) => {
    await page.goto('/plugins?type=PROCESS_EXECUTOR');
    await page.getByRole('tab', { name: /Ports/ }).click();
    await expect(page.getByText('A port is a named result')).toBeVisible();
    await expect(page.getByText('success, failure')).toBeVisible();

    await page.goto('/plugins?type=JSON_TRANSFORM');
    await expect(page.getByText('Output ports: default')).toBeVisible();

    await page.goto('/plugins?type=CONSOLE_TERMINAL');
    await expect(page.getByText('None (terminal)')).toBeVisible();
  });

  test('Example shows a node with the required and defaulted fields, and the usage pattern', async ({
    page,
  }) => {
    await page.goto('/plugins?type=PROCESS_EXECUTOR');
    await page.getByRole('tab', { name: 'Example' }).click();

    const example = page.getByText('"nodeId": "process_executor"');
    await expect(example).toBeVisible();
    await expect(page.getByText('"outputFormat": "RAW"')).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Usage' })).toBeVisible();
    await expect(page.getByText('command: [echo, hi]')).toBeVisible();
  });

  test('Example has no Usage section without a pattern, and the node JSON can be copied', async ({
    page,
    context,
  }) => {
    await context.grantPermissions(['clipboard-read', 'clipboard-write']);
    await page.goto('/plugins?type=JSON_TRANSFORM');
    await page.getByRole('tab', { name: 'Example' }).click();
    await expect(page.getByRole('heading', { name: 'Usage' })).toHaveCount(0);

    await page.getByRole('button', { name: 'Copy node JSON' }).click();

    await expect(page.getByRole('status')).toHaveText('Copied');
    const copied = JSON.parse(
      await page.evaluate(() => navigator.clipboard.readText())
    ) as { type: string; nodeId: string };
    expect(copied).toMatchObject({
      type: 'JSON_TRANSFORM',
      nodeId: 'json_transform',
    });
  });

  test('Used in lists the workflows that use the plugin, each linking to it', async ({
    page,
    mockWorkflowApi,
  }) => {
    await mockWorkflowApi(page);
    await page.goto('/plugins?type=PROCESS_EXECUTOR');
    const tab = page.getByRole('tab', { name: /Used in/ });
    await expect(tab).toContainText('2');

    await tab.click();
    await expect(page.getByText('3 nodes')).toBeVisible();
    await expect(page.getByText('1 node', { exact: true })).toBeVisible();
    await page.getByRole('link', { name: 'build / check' }).click();

    await expect(page).toHaveURL(/\/sessions\/build\/workflow\/check$/);
  });

  test('Used in says when nothing uses the plugin', async ({ page }) => {
    await page.goto('/plugins?type=JSON_TRANSFORM');

    await page.getByRole('tab', { name: /Used in/ }).click();

    await expect(
      page.getByText('No workflow uses JSON_TRANSFORM yet.')
    ).toBeVisible();
  });

  test('Used in says when the usage cannot be loaded', async ({ page }) => {
    await mockPlugins(page, { usageStatus: 500 });
    await page.goto('/plugins?type=JSON_TRANSFORM');

    await page.getByRole('tab', { name: /Used in/ }).click();

    await expect(
      page.getByText('Failed to load where it is used.')
    ).toBeVisible();
  });

  test('choosing another plugin starts on its own first tab', async ({
    page,
  }) => {
    await page.goto('/plugins?type=PROCESS_EXECUTOR');
    await page.getByRole('tab', { name: 'Example' }).click();

    await item(page, 'JSON_TRANSFORM').click();

    await expect(page.getByRole('tab', { name: /Ports/ })).toHaveAttribute(
      'aria-selected',
      'true'
    );
  });
});
