// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { expect, test, type Page } from '@playwright/test';

/**
 * Runs against a real server (see playwright.real.config.ts). Every session it makes is named
 * `e2e-<template>-<run>` and deleted afterwards, so it can be repeated on the same server.
 */

interface Template {
  id: string;
  title: string;
  pluginTypes: string[];
}

const run = Date.now().toString(36);
const created: string[] = [];

const example = (path: string): string =>
  readFileSync(
    fileURLToPath(new URL(`../../../examples/${path}`, import.meta.url)),
    'utf8'
  );

/** The page's console errors; a real server has no business logging any. */
function watchConsole(page: Page): string[] {
  const errors: string[] = [];
  page.on('console', (message) => {
    if (message.type() === 'error') errors.push(message.text());
  });
  page.on('pageerror', (error) => errors.push(error.message));
  return errors;
}

/**
 * Step 1 of New session: pick the template's card (the gallery lists them in the API's order), then
 * give the session its own ID and create it.
 */
async function createFromTemplate(
  page: Page,
  templates: Template[],
  id: string
) {
  const index = templates.findIndex((t) => t.id === id);
  expect(index, `template ${id} is offered`).toBeGreaterThanOrEqual(0);
  await page.goto('/sessions/new');
  await page.getByRole('button', { name: 'Use template' }).nth(index).click();
  const sessionId = `e2e-${id}-${run}`;
  await page.getByLabel('Session ID').fill(sessionId);
  await page.getByRole('button', { name: 'Create session' }).click();
  await page.waitForURL(`**/sessions/${sessionId}/workflow/**`);
  created.push(sessionId);
  return sessionId;
}

/** Starts the open workflow from the run dialog; returns the execution ID from the URL. */
async function startRun(page: Page, payload?: string): Promise<string> {
  await page.locator('main button:has-text("play_arrow")').first().click();
  const dialog = page.getByRole('dialog');
  if (payload) await dialog.locator('textarea').fill(payload);
  await dialog.getByRole('button', { name: 'Run', exact: true }).click();
  await page.waitForURL(/[?&]exec=/);
  return new URL(page.url()).searchParams.get('exec') ?? '';
}

async function status(page: Page, executionId: string): Promise<string> {
  const response = await page.request.get(`/api/executions/${executionId}`);
  return (
    ((await response.json()) as { data?: { status?: string } }).data?.status ??
    ''
  );
}

test.describe('examples from the UI on a real server', () => {
  let templates: Template[] = [];

  test.beforeAll(async ({ request }) => {
    const response = await request.get('/api/templates');
    templates = ((await response.json()) as { data: Template[] }).data;
  });

  test.afterAll(async ({ request }) => {
    for (const id of created) await request.delete(`/api/sessions/${id}`);
  });

  test('the server offers the bundled examples as templates', () => {
    expect(templates.length).toBeGreaterThanOrEqual(13);
  });

  test('every template creates a session and opens its first workflow', async ({
    page,
  }) => {
    const errors = watchConsole(page);
    const failures: string[] = [];
    for (const template of templates) {
      // AGENT_SESSION needs an agent CLI configured on the server
      if (template.pluginTypes.includes('AGENT_SESSION')) continue;
      try {
        await createFromTemplate(page, templates, template.id);
      } catch (e) {
        failures.push(`${template.id}: ${(e as Error).message.split('\n')[0]}`);
      }
    }
    expect(failures).toEqual([]);
    expect(errors).toEqual([]);
  });

  test('01 hello world runs and prints its greeting', async ({ page }) => {
    const errors = watchConsole(page);
    await createFromTemplate(page, templates, '01-hello-world');
    const executionId = await startRun(page);
    await expect(page.getByText('Hello from Yukta').first()).toBeVisible();
    await expect.poll(() => status(page, executionId)).toBe('SUCCESS');
    expect(errors).toEqual([]);
  });

  for (const [id, payload] of [
    ['05-dependency-updates', '05-dependency-updates/patch-bump.json'],
    ['13-multi-agent', '13-multi-agent/request.json'],
  ] as const) {
    test(`${id} runs with its sample payload`, async ({ page }) => {
      const errors = watchConsole(page);
      await createFromTemplate(page, templates, id);
      const executionId = await startRun(page, example(payload));
      await expect.poll(() => status(page, executionId)).toBe('SUCCESS');
      expect(errors).toEqual([]);
    });
  }

  test('04 a borderline PR waits for a reviewer, who answers in the UI', async ({
    page,
  }) => {
    const errors = watchConsole(page);
    await createFromTemplate(page, templates, '04-pr-risk-gate');
    const executionId = await startRun(
      page,
      example('04-pr-risk-gate/borderline.json')
    );
    await expect(page.getByText('Waiting for an answer')).toBeVisible();
    await page.getByRole('button', { name: 'Approve', exact: true }).click();
    // a HIGH risk answer asks for confirmation first
    await page
      .getByRole('dialog')
      .getByRole('button', { name: 'Confirm' })
      .click();
    await expect(
      page.getByText('No input requests are waiting.')
    ).toBeVisible();
    await expect.poll(() => status(page, executionId)).toBe('SUCCESS');
    expect(errors).toEqual([]);
  });
});
