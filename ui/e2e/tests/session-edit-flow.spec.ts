// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { Page } from '@playwright/test';
import { test, expect } from '../fixtures';
import { envelope } from '../api-envelope';

const CONFIG = {
  sessionId: 'session-1',
  name: 'Build Pipeline',
  description: 'Compiles and tests the app',
  initiator: 'ci',
  tags: { team: 'core', urgent: '' },
  projectPath: '/srv/repos/app',
  workflows: {
    build: {
      workflowId: 'build',
      description: 'Build it',
      nodes: [
        { nodeId: 'start', type: 'CONSTANT_SOURCE', config: {} },
        { nodeId: 'end', type: 'CONSOLE_TERMINAL', config: {} },
      ],
      edges: [{ source: 'start', target: 'end' }],
    },
    test: {
      workflowId: 'test',
      description: 'Test it',
      nodes: [{ nodeId: 'only', type: 'CONSOLE_TERMINAL', config: {} }],
      edges: [],
    },
  },
};

type Saved = typeof CONFIG;

/**
 * The edit page's calls. `configs` answers each load in turn (the last one repeats), so a test can
 * change the session between opening it and saving it.
 */
async function mockEdit(
  page: Page,
  {
    configs = [CONFIG],
    saveStatus = 200,
    directory = { exists: true, directory: true },
  }: {
    configs?: unknown[];
    saveStatus?: number;
    directory?: { exists: boolean; directory: boolean };
  } = {}
): Promise<{ saves: Saved[] }> {
  const saves: Saved[] = [];
  let loads = 0;
  await page.route('**/api/sessions/session-1/config', (route) =>
    route.fulfill(
      envelope('/config', configs[Math.min(loads++, configs.length - 1)])
    )
  );
  await page.route('**/api/sessions/validate/project-path', (route) =>
    route.fulfill(envelope('/project-path', directory))
  );
  await page.route('**/api/sessions', (route) => {
    if (route.request().method() !== 'POST') return route.fallback();
    saves.push(route.request().postDataJSON());
    return saveStatus === 200
      ? route.fulfill(envelope('/sessions', null))
      : route.fulfill({
          status: saveStatus,
          contentType: 'application/json',
          body: JSON.stringify({
            message: 'Validation failed',
            errors: [{ field: 'projectPath', message: 'is not allowed' }],
          }),
        });
  });
  return { saves };
}

const save = (page: Page) => page.getByRole('button', { name: 'Save' });

test.describe('Edit session - the form', () => {
  test.beforeEach(async ({ page, mockSessionsApi }) => {
    await mockSessionsApi(page, {});
    await mockEdit(page);
    await page.goto('/sessions/session-1/edit');
  });

  test('shows the session in a titled form with every field filled', async ({
    page,
  }) => {
    await expect(
      page.getByRole('heading', { level: 1, name: 'Edit session-1' })
    ).toBeVisible();
    await expect(page.getByLabel('Name')).toHaveValue('Build Pipeline');
    await expect(page.getByLabel('Session ID')).toHaveValue('session-1');
    await expect(page.getByLabel('Description')).toHaveValue(
      'Compiles and tests the app'
    );
    await expect(page.getByLabel('Working directory')).toHaveValue(
      '/srv/repos/app'
    );
    await expect(page.getByLabel('Initiator')).toHaveValue('ci');
    await expect(
      page.getByRole('button', { name: 'team: core' })
    ).toBeVisible();
    await expect(
      page.getByRole('button', { name: 'urgent', exact: true })
    ).toBeVisible();
    await expect(save(page)).toBeVisible();
  });

  test('the session ID cannot be changed and says why', async ({ page }) => {
    await expect(page.getByLabel('Session ID')).toHaveAttribute('readonly', '');
    await expect(
      page.getByText(
        "Used in URLs, the CLI and MCP calls; it can't be changed."
      )
    ).toBeVisible();
  });

  test('renaming does not touch the session ID', async ({ page }) => {
    await page.getByLabel('Name').fill('Something completely different');

    await expect(page.getByLabel('Session ID')).toHaveValue('session-1');
  });

  test('has no scripted panel and no template step', async ({ page }) => {
    await expect(
      page.getByRole('heading', { name: 'Same thing, scripted' })
    ).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Start blank' })).toHaveCount(
      0
    );
  });

  test('Cancel returns to the session page', async ({ page }) => {
    await page.getByRole('button', { name: 'Cancel' }).click();

    await expect(page).toHaveURL(/\/sessions\/session-1$/);
  });

  test('a working directory the server does not have is flagged', async ({
    page,
  }) => {
    await mockEdit(page, { directory: { exists: false, directory: false } });
    await page.goto('/sessions/session-1/edit');

    const path = page.getByLabel('Working directory');
    await path.fill('/gone/away');
    await path.blur();

    await expect(
      page.getByText("The server can't find /gone/away.")
    ).toBeVisible();
  });
});

test.describe('Edit session - saving', () => {
  test('sends the edited fields and keeps every workflow as it was', async ({
    page,
    mockSessionsApi,
  }) => {
    await mockSessionsApi(page, {});
    const { saves } = await mockEdit(page);
    await page.goto('/sessions/session-1/edit');

    await page.getByLabel('Name').fill('Build and release');
    await page.getByLabel('Description').fill('Now releases too');
    await page.getByLabel('Initiator').fill('release-bot');
    await save(page).click();

    await expect(page).toHaveURL(/\/sessions\/session-1$/);
    expect(saves).toHaveLength(1);
    expect(saves[0]).toMatchObject({
      sessionId: 'session-1',
      name: 'Build and release',
      description: 'Now releases too',
      initiator: 'release-bot',
      projectPath: '/srv/repos/app',
      tags: { team: 'core', urgent: '' },
    });
    expect(saves[0].workflows).toEqual(CONFIG.workflows);
  });

  test('tags can be removed and added', async ({ page, mockSessionsApi }) => {
    await mockSessionsApi(page, {});
    const { saves } = await mockEdit(page);
    await page.goto('/sessions/session-1/edit');

    await page.getByRole('button', { name: 'Remove tag team' }).click();
    const add = page.getByLabel('Add a tag');
    await add.fill('env: prod');
    await add.press('Enter');
    await save(page).click();

    await expect(page).toHaveURL(/\/sessions\/session-1$/);
    expect(saves[0].tags).toEqual({ urgent: '', env: 'prod' });
  });

  test('a field left empty keeps the form from being sent', async ({
    page,
    mockSessionsApi,
  }) => {
    await mockSessionsApi(page, {});
    const { saves } = await mockEdit(page);
    await page.goto('/sessions/session-1/edit');

    await page.getByLabel('Name').fill('');
    await save(page).click();

    await expect(page.getByLabel('Name')).toBeFocused();
    expect(saves).toHaveLength(0);
  });

  test('a server error is shown with its field and the form stays editable', async ({
    page,
  }) => {
    await mockEdit(page, { saveStatus: 400 });
    await page.goto('/sessions/session-1/edit');

    await save(page).click();

    await expect(page.getByText('projectPath: is not allowed')).toBeVisible();
    await expect(save(page)).toBeEnabled();
    await expect(page).toHaveURL(/\/sessions\/session-1\/edit$/);
  });

  test('refuses to overwrite a session that changed since it was opened, then saves on the second try', async ({
    page,
    mockSessionsApi,
  }) => {
    await mockSessionsApi(page, {});
    const changed = { ...CONFIG, description: 'Edited by someone else' };
    const { saves } = await mockEdit(page, { configs: [CONFIG, changed] });
    await page.goto('/sessions/session-1/edit');
    await page.getByLabel('Name').fill('My edit');

    await save(page).click();

    await expect(
      page.getByText(/This session changed since you opened it\./)
    ).toBeVisible();
    expect(saves).toHaveLength(0);
    await expect(page).toHaveURL(/\/sessions\/session-1\/edit$/);

    await save(page).click();

    await expect(page).toHaveURL(/\/sessions\/session-1$/);
    expect(saves).toHaveLength(1);
    expect(saves[0].name).toBe('My edit');
    // the edits are on top of the newer version
    expect(saves[0].workflows).toEqual(CONFIG.workflows);
  });
});

test.describe('Edit session - states', () => {
  test('shows a loading state while the session loads', async ({ page }) => {
    await mockEdit(page);
    await page.route('**/api/sessions/session-1/config', async (route) => {
      await new Promise((resolve) => setTimeout(resolve, 800));
      return route.fulfill(envelope('/config', CONFIG));
    });
    await page.goto('/sessions/session-1/edit');

    await expect(page.getByText('Loading…')).toBeVisible();
    await expect(page.getByLabel('Name')).toHaveValue('Build Pipeline');
  });

  test('a VIEWER key cannot save', async ({ page }) => {
    await mockEdit(page);
    await page.route('**/api/me', (route) =>
      route.fulfill(
        envelope('/api/me', {
          authenticated: true,
          keyName: 'reader',
          role: 'VIEWER',
          sessions: null,
          authEnabled: true,
          permissions: {
            startRuns: false,
            controlRuns: false,
            editSessions: false,
            deleteSessions: false,
            changeLimits: false,
            sendCommands: false,
          },
        })
      )
    );
    await page.goto('/sessions/session-1/edit');

    await expect(save(page)).toBeDisabled();
    await expect(save(page)).toHaveAttribute('title', /^Needs /);
  });
});
