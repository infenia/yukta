// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { Page } from '@playwright/test';
import { test, expect } from '../fixtures';
import { envelope } from '../api-envelope';

const sse = (...events: object[]) =>
  events.map((e) => `data: ${JSON.stringify(e)}\n\n`).join('');

const text = (t: string) =>
  sse(
    { type: 'content_block_start', index: 0, content_block: { type: 'text' } },
    {
      type: 'content_block_delta',
      index: 0,
      delta: { type: 'text_delta', text: t },
    },
    { type: 'content_block_stop', index: 0 }
  );

const toolUse = (name: string, input: object) =>
  sse(
    {
      type: 'content_block_start',
      index: 0,
      content_block: { type: 'tool_use', id: 't1', name, input: {} },
    },
    {
      type: 'content_block_delta',
      index: 0,
      delta: { type: 'input_json_delta', partial_json: JSON.stringify(input) },
    },
    { type: 'content_block_stop', index: 0 }
  );

interface Mock {
  /** The model replies in order: the key test, then each turn. */
  proxyBodies: Record<string, unknown>[];
  toolCalls: string[];
}

async function mockAssistant(page: Page, replies: string[]): Promise<Mock> {
  const mock: Mock = { proxyBodies: [], toolCalls: [] };
  // a browser with a built-in model starts on it; these tests use a provider key
  await page.addInitScript(() =>
    localStorage.setItem(
      'yukta.assistant',
      JSON.stringify({ provider: 'anthropic', model: 'claude-sonnet-5-5' })
    )
  );
  await page.route('**/api/sessions/summaries*', (r) =>
    r.fulfill(envelope('/s', []))
  );
  await page.route('**/api/assistant/proxy/anthropic', (r) => {
    mock.proxyBodies.push(r.request().postDataJSON());
    return r.fulfill({
      contentType: 'text/event-stream',
      body: replies[mock.proxyBodies.length - 1] ?? text('Done.'),
    });
  });
  await page.route('**/mcp', (r) => {
    const { id, method, params } = r.request().postDataJSON();
    const result =
      method === 'tools/list'
        ? {
            tools: [
              {
                name: 'list_workflows',
                description: 'Lists workflows',
                inputSchema: { type: 'object', properties: {} },
              },
              {
                name: 'control_workflow',
                description: 'Stops or resumes a run',
                inputSchema: { type: 'object', properties: {} },
              },
            ],
          }
        : (mock.toolCalls.push(params.name),
          { content: [{ type: 'text', text: 'stopped' }] });
    return r.fulfill({ json: { jsonrpc: '2.0', id, result } });
  });
  return mock;
}

const panel = (page: Page) =>
  page.getByRole('dialog', { name: 'Yukta assistant' });

/** Opens the card with the keyboard and saves a key, which makes one test call. */
async function setUp(page: Page) {
  await page.goto('/sessions');
  // the shortcut is live once the app has mounted, which the button shows
  await expect(
    page.getByRole('button', { name: 'Yukta assistant' })
  ).toBeVisible();
  await page.keyboard.press('Control+j');
  await expect(panel(page)).toBeVisible();
  await panel(page).getByLabel('API key').fill('sk-test');
  await panel(page).getByRole('button', { name: 'Save and test' }).click();
  await expect(panel(page).getByLabel('Message')).toBeVisible();
}

test.describe('Assistant', () => {
  test('is on every page, opens with Ctrl+J and asks for a key first', async ({
    page,
  }) => {
    await mockAssistant(page, [text('OK')]);
    await page.goto('/sessions');
    await expect(
      page.getByRole('button', { name: 'Yukta assistant' })
    ).toBeVisible();
    await page.keyboard.press('Control+j');
    await expect(panel(page).getByLabel('API key')).toBeVisible();
    await page.keyboard.press('Control+j');
    await expect(panel(page)).toBeHidden();
  });

  test('sends the key in a header, never in the body', async ({ page }) => {
    const mock = await mockAssistant(page, [text('OK')]);
    const header = page.waitForRequest((req) =>
      req.url().includes('/api/assistant/proxy/anthropic')
    );
    await setUp(page);
    expect((await header).headers()['x-assistant-key']).toBe('sk-test');
    expect(JSON.stringify(mock.proxyBodies)).not.toContain('sk-test');
  });

  test('answers a question', async ({ page }) => {
    await mockAssistant(page, [text('OK'), text('Two workflows are running.')]);
    await setUp(page);
    await panel(page).getByLabel('Message').fill('What is running?');
    await panel(page).getByRole('button', { name: 'Send' }).click();
    await expect(panel(page)).toContainText('Two workflows are running.');
  });

  test('asks before a write and runs it only on Approve', async ({ page }) => {
    const mock = await mockAssistant(page, [
      text('OK'),
      toolUse('control_workflow', { action: 'stop' }),
      text('Stopped it.'),
    ]);
    await setUp(page);
    await panel(page).getByLabel('Message').fill('Stop the build');
    await panel(page).getByRole('button', { name: 'Send' }).click();
    await expect(
      panel(page).getByRole('button', { name: 'Deny' })
    ).toBeVisible();
    expect(mock.toolCalls).toEqual([]);
    await panel(page).getByRole('button', { name: 'Approve' }).click();
    await expect(panel(page)).toContainText('Stopped it.');
    expect(mock.toolCalls).toEqual(['control_workflow']);
  });

  test('runs nothing when a write is denied', async ({ page }) => {
    const mock = await mockAssistant(page, [
      text('OK'),
      toolUse('control_workflow', { action: 'stop' }),
      text('Understood.'),
    ]);
    await setUp(page);
    await panel(page).getByLabel('Message').fill('Stop the build');
    await panel(page).getByRole('button', { name: 'Send' }).click();
    await panel(page).getByRole('button', { name: 'Deny' }).click();
    await expect(panel(page)).toContainText('Understood.');
    expect(mock.toolCalls).toEqual([]);
  });
});
