// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { Page } from '@playwright/test';
import { test, expect } from '../fixtures';
import { envelope } from '../api-envelope';

const TEMPLATE = {
  id: '01-hello-world',
  title: 'Hello world',
  summary: 'Smallest possible workflow',
  pluginTypes: ['CONSTANT_SOURCE', 'CONSOLE_TERMINAL'],
};

const TEMPLATE_DETAIL = {
  ...TEMPLATE,
  readme: '# 01 — Hello world\n\nThe smallest workflow.',
  session: {
    sessionId: 'example-01',
    name: 'Hello world',
    description: 'Smallest possible workflow',
    initiator: 'examples',
    projectPath: '/tmp',
    tags: { example: '' },
    workflows: {
      hello: {
        workflowId: 'hello',
        description: 'Echo',
        nodes: [
          { nodeId: 'start', type: 'CONSTANT_SOURCE', config: {} },
          { nodeId: 'end', type: 'CONSOLE_TERMINAL', config: {} },
        ],
        edges: [{ source: 'start', target: 'end', sourcePort: null }],
      },
    },
  },
};

interface Saved {
  sessionId?: string;
  name?: string;
  description?: string;
  initiator?: string;
  projectPath?: string;
  tags?: Record<string, string>;
  workflows?: Record<string, unknown>;
}

/** Templates and the working-directory check; `saves` collects what the form POSTs. */
async function mockNew(
  page: Page,
  {
    templates = [TEMPLATE],
    directory = { exists: true, directory: true },
  }: {
    templates?: unknown[];
    directory?: { exists: boolean; directory: boolean };
  } = {}
): Promise<{ saves: Saved[] }> {
  const saves: Saved[] = [];
  await page.route('**/api/plugins', (route) =>
    route.fulfill(
      envelope('/plugins', [
        { type: 'CONSTANT_SOURCE', category: 'TRIGGER' },
        { type: 'CONSOLE_TERMINAL', category: 'TERMINAL' },
      ])
    )
  );
  await page.route('**/api/templates', (route) =>
    route.fulfill(envelope('/templates', templates))
  );
  await page.route('**/api/templates/01-hello-world', (route) =>
    route.fulfill(envelope('/templates/01-hello-world', TEMPLATE_DETAIL))
  );
  await page.route('**/api/sessions/validate/project-path', (route) =>
    route.fulfill(envelope('/project-path', directory))
  );
  await page.route('**/api/sessions', (route) => {
    if (route.request().method() !== 'POST') return route.fallback();
    saves.push(route.request().postDataJSON());
    return route.fulfill(envelope('/sessions', null));
  });
  return { saves };
}

const startBlank = async (page: Page) => {
  await page.goto('/sessions/new');
  await page.getByRole('button', { name: 'Start blank' }).click();
};

test.describe('New session - choosing a start', () => {
  test('shows the page header, the blank card and every template', async ({
    page,
  }) => {
    await mockNew(page);
    await page.goto('/sessions/new');

    await expect(
      page.getByRole('heading', { level: 1, name: 'New session' })
    ).toBeVisible();
    await expect(page.getByText('Choose a start.')).toBeVisible();
    await expect(
      page.getByRole('heading', { name: 'Blank session' })
    ).toBeVisible();
    await expect(
      page.getByText('A trigger wired to a console terminal.')
    ).toBeVisible();
    await expect(
      page.getByRole('heading', { name: 'Hello world' })
    ).toBeVisible();
    await expect(page.getByText('Smallest possible workflow')).toBeVisible();
    const types = page.getByRole('list', { name: 'Plugin types' });
    await expect(types.getByRole('listitem')).toHaveText([
      'CONSTANT_SOURCE',
      'CONSOLE_TERMINAL',
    ]);
  });

  test('Import session leads to the import page', async ({ page }) => {
    await mockNew(page);
    await page.goto('/sessions/new');

    await page.getByRole('link', { name: 'Import session' }).click();

    await expect(page).toHaveURL(/\/sessions\/import$/);
  });

  test('shows a loading state while the templates load', async ({ page }) => {
    await mockNew(page);
    await page.route('**/api/templates', async (route) => {
      await new Promise((resolve) => setTimeout(resolve, 800));
      return route.fulfill(envelope('/templates', [TEMPLATE]));
    });
    await page.goto('/sessions/new');

    await expect(page.getByText('Loading templates…')).toBeVisible();
    await expect(
      page.getByRole('heading', { name: 'Hello world' })
    ).toBeVisible();
  });

  test('when the templates fail, says so and still lets you start blank', async ({
    page,
  }) => {
    await mockNew(page);
    await page.route('**/api/templates', (route) =>
      route.fulfill({
        status: 500,
        contentType: 'application/json',
        body: JSON.stringify({ message: 'boom' }),
      })
    );
    await page.goto('/sessions/new');

    await expect(
      page.getByText(
        'Templates could not be loaded; you can still start blank.'
      )
    ).toBeVisible();
    await page.getByRole('button', { name: 'Start blank' }).click();
    await expect(page.getByLabel('Session ID')).toBeVisible();
  });

  test('with no templates only the blank card is offered', async ({ page }) => {
    await mockNew(page, { templates: [] });
    await page.goto('/sessions/new');

    await expect(
      page.getByRole('button', { name: 'Use template' })
    ).toHaveCount(0);
    await expect(
      page.getByRole('button', { name: 'Start blank' })
    ).toBeVisible();
  });

  test('a template that cannot be fetched shows why', async ({ page }) => {
    await mockNew(page);
    await page.route('**/api/templates/01-hello-world', (route) =>
      route.fulfill({
        status: 404,
        contentType: 'application/json',
        body: JSON.stringify({ message: 'Template not found' }),
      })
    );
    await page.goto('/sessions/new');

    await page.getByRole('button', { name: 'Use template' }).click();

    await expect(page.getByText('Template not found')).toBeVisible();
    await expect(page.getByLabel('Session ID')).toHaveCount(0);
  });

  test('the preview closes with Close', async ({ page }) => {
    await mockNew(page);
    await page.goto('/sessions/new');
    await page.getByRole('button', { name: 'Preview' }).click();
    const dialog = page.getByRole('dialog', { name: 'Hello world' });
    await expect(dialog).toBeVisible();

    await dialog.getByRole('button', { name: 'Close', exact: true }).click();

    await expect(dialog).toBeHidden();
    await expect(page.getByLabel('Session ID')).toHaveCount(0);
  });

  test('"Use this template" in the preview fills the form from it', async ({
    page,
  }) => {
    await mockNew(page);
    await page.goto('/sessions/new');
    await page.getByRole('button', { name: 'Preview' }).click();

    await page
      .getByRole('dialog', { name: 'Hello world' })
      .getByRole('button', { name: 'Use this template' })
      .click();

    await expect(page.getByLabel('Name')).toHaveValue('Hello world');
    await expect(page.getByLabel('Session ID')).toHaveValue('01-hello-world-1');
    await expect(page.getByLabel('Description')).toHaveValue(
      'Smallest possible workflow'
    );
    await expect(
      page.getByRole('button', { name: 'example' }).first()
    ).toBeVisible();
  });
});

test.describe('New session - the form', () => {
  test.beforeEach(async ({ page }) => {
    await mockNew(page, { templates: [] });
  });

  test('starts with a blank form, ui as the initiator and its hints', async ({
    page,
  }) => {
    await startBlank(page);

    await expect(page.getByLabel('Name')).toHaveValue('');
    await expect(page.getByLabel('Session ID')).toHaveValue('');
    await expect(page.getByLabel('Initiator')).toHaveValue('ui');
    await expect(page.getByLabel('Working directory')).toHaveValue('');
    await expect(page.getByText(/Made from the name\./)).toBeVisible();
    await expect(
      page.getByText('Absolute path on the server, not on your machine')
    ).toBeVisible();
    await expect(page.getByText('Who or what starts its runs')).toBeVisible();
    await expect(
      page.getByRole('heading', { name: 'Supervisors' })
    ).toBeVisible();
  });

  test('the session ID follows the name until you edit it', async ({
    page,
  }) => {
    await startBlank(page);
    const id = page.getByLabel('Session ID');

    await page.getByLabel('Name').fill('Payments  Nightly Build!');
    await expect(id).toHaveValue('payments-nightly-build');

    await id.fill('custom-id');
    await page.getByLabel('Name').fill('Something else');
    await expect(id).toHaveValue('custom-id');
  });

  test('Cancel goes back to the session list', async ({ page }) => {
    await startBlank(page);

    await page.getByRole('button', { name: 'Cancel' }).click();

    await expect(page).toHaveURL(/\/sessions$/);
  });

  test('every text field is required: nothing is sent while one is empty', async ({
    page,
  }) => {
    const { saves } = await mockNew(page, { templates: [] });
    await startBlank(page);
    await page.getByLabel('Name').fill('Only a name');

    await page.getByRole('button', { name: 'Create session' }).click();

    await expect(page.getByLabel('Description')).toBeFocused();
    expect(saves).toHaveLength(0);
    await expect(page).toHaveURL(/\/sessions\/new$/);
  });

  test('creates the session with a starter workflow and opens its editor', async ({
    page,
  }) => {
    const { saves } = await mockNew(page, { templates: [] });
    await page.route('**/api/sessions/pay/config', (route) =>
      route.fulfill(
        envelope('/config', {
          sessionId: 'pay',
          name: 'Pay',
          description: 'd',
          initiator: 'ui',
          tags: {},
          projectPath: '/srv/pay',
          workflows: {},
        })
      )
    );
    await startBlank(page);
    await page.getByLabel('Name').fill('Pay');
    await page.getByLabel('Session ID').fill('pay');
    await page.getByLabel('Description').fill('Payments checks');
    await page.getByLabel('Working directory').fill('/srv/pay');
    await page.getByLabel('Initiator').fill('alice');

    await page.getByRole('button', { name: 'Create session' }).click();

    await expect(page).toHaveURL(/\/sessions\/pay\/workflow\/main\/edit$/);
    expect(saves).toHaveLength(1);
    expect(saves[0]).toMatchObject({
      sessionId: 'pay',
      name: 'Pay',
      description: 'Payments checks',
      initiator: 'alice',
      projectPath: '/srv/pay',
      tags: {},
    });
    expect(Object.keys(saves[0].workflows ?? {})).toEqual(['main']);
  });

  test('creating from a template opens its first workflow', async ({
    page,
    mockWorkflowApi,
  }) => {
    const { saves } = await mockNew(page);
    await mockWorkflowApi(page);
    await page.goto('/sessions/new');
    await page.getByRole('button', { name: 'Use template' }).click();
    await page.getByLabel('Working directory').fill('/srv/demo');

    await page.getByRole('button', { name: 'Create session' }).click();

    await expect(page).toHaveURL(
      /\/sessions\/01-hello-world-1\/workflow\/hello$/
    );
    expect(saves[0].tags).toEqual({ example: '' });
  });

  test('a server error is shown with its fields, and the form can be sent again', async ({
    page,
  }) => {
    await startBlank(page);
    await page.route('**/api/sessions', (route) =>
      route.request().method() === 'POST'
        ? route.fulfill({
            status: 400,
            contentType: 'application/json',
            body: JSON.stringify({
              message: 'Validation failed',
              errors: [{ field: 'name', message: 'must not be blank' }],
            }),
          })
        : route.fallback()
    );
    await page.getByLabel('Name').fill('Pay');
    await page.getByLabel('Description').fill('d');
    await page.getByLabel('Working directory').fill('/srv/pay');

    await page.getByRole('button', { name: 'Create session' }).click();

    await expect(page.getByText('name: must not be blank')).toBeVisible();
    await expect(
      page.getByRole('button', { name: 'Create session' })
    ).toBeEnabled();
    await expect(page).toHaveURL(/\/sessions\/new$/);
  });

  test('says when the working directory is a file', async ({ page }) => {
    await mockNew(page, {
      templates: [],
      directory: { exists: true, directory: false },
    });
    await startBlank(page);

    const path = page.getByLabel('Working directory');
    await path.fill('/etc/hosts');
    await path.blur();

    await expect(
      page.getByText('/etc/hosts is a file, not a directory.')
    ).toBeVisible();
    await expect(path).toHaveAttribute('aria-invalid', 'true');
  });
});

test.describe('New session - tags', () => {
  test.beforeEach(async ({ page }) => {
    await mockNew(page, { templates: [] });
    await startBlank(page);
  });

  test('Enter or a comma adds a tag chip, and the tags are sent with the session', async ({
    page,
  }) => {
    const { saves } = await mockNew(page, { templates: [] });
    await page.route('**/api/sessions/t/config', (route) =>
      route.fulfill(
        envelope('/config', {
          sessionId: 't',
          name: 'T',
          description: 'd',
          initiator: 'ui',
          tags: {},
          projectPath: '/srv',
          workflows: {},
        })
      )
    );
    const add = page.getByLabel('Add a tag');
    await add.fill('env: prod');
    await add.press('Enter');
    await add.fill('team: payments');
    await add.press(',');
    await add.fill('urgent');
    await add.press('Enter');

    await expect(page.getByRole('button', { name: 'env: prod' })).toBeVisible();
    await expect(
      page.getByRole('button', { name: 'team: payments' })
    ).toBeVisible();
    await expect(
      page.getByRole('button', { name: 'urgent', exact: true })
    ).toBeVisible();
    await expect(add).toHaveValue('');

    await page.getByLabel('Name').fill('T');
    await page.getByLabel('Session ID').fill('t');
    await page.getByLabel('Description').fill('d');
    await page.getByLabel('Working directory').fill('/srv');
    await page.getByRole('button', { name: 'Create session' }).click();

    await expect(page).toHaveURL(/\/sessions\/t\/workflow\/main\/edit$/);
    expect(saves[0].tags).toEqual({
      env: 'prod',
      team: 'payments',
      urgent: '',
    });
  });

  test('the × button removes a tag', async ({ page }) => {
    const add = page.getByLabel('Add a tag');
    await add.fill('env: prod');
    await add.press('Enter');

    await page.getByRole('button', { name: 'Remove tag env' }).click();

    await expect(page.getByRole('button', { name: 'env: prod' })).toHaveCount(
      0
    );
  });

  test('clicking a chip puts it back in the box to edit', async ({ page }) => {
    const add = page.getByLabel('Add a tag');
    await add.fill('env: prod');
    await add.press('Enter');

    await page.getByRole('button', { name: 'env: prod' }).click();

    await expect(add).toHaveValue('env: prod');
    await expect(page.getByRole('button', { name: 'env: prod' })).toHaveCount(
      0
    );
  });

  test('a tag without a key and a repeated key are refused', async ({
    page,
  }) => {
    const add = page.getByLabel('Add a tag');

    await add.fill(': orphan');
    await add.press('Enter');
    await expect(
      page.getByText('A tag needs a key: write key: value.')
    ).toBeVisible();

    await add.fill('env: prod');
    await add.press('Enter');
    await add.fill('env: dev');
    await add.press('Enter');
    await expect(
      page.getByText('The tag key "env" is used already.')
    ).toBeVisible();
    await expect(page.getByRole('button', { name: 'env: dev' })).toHaveCount(0);
  });
});

test.describe('New session - supervisors', () => {
  const supervisor = (id: string) => ({
    id,
    type: 'rules',
    description: null,
    settings: {},
    dryRun: false,
    system: false,
    attachments: 0,
    applied: 0,
    rejected: 0,
    recorded: 0,
    rateLimited: 0,
  });

  test.beforeEach(async ({ page }) => {
    await mockNew(page, { templates: [] });
    await page.route('**/api/supervisors', (route) =>
      route.fulfill(
        envelope('/api/supervisors', [supervisor('gate'), supervisor('audit')])
      )
    );
  });

  test('a supervisor row is added with the first unused one and removed', async ({
    page,
  }) => {
    await startBlank(page);

    await page.getByRole('button', { name: 'Add supervisor' }).click();
    await expect(page.getByLabel('Supervisor 1', { exact: true })).toHaveValue(
      'gate'
    );

    await page.getByRole('button', { name: 'Add supervisor' }).click();
    await expect(page.getByLabel('Supervisor 2', { exact: true })).toHaveValue(
      'audit'
    );
    await expect(
      page.getByRole('button', { name: 'Add supervisor' })
    ).toBeDisabled();

    await page.getByRole('button', { name: 'Remove supervisor 1' }).click();
    await expect(page.getByLabel('Supervisor 2', { exact: true })).toHaveCount(
      0
    );
    await expect(page.getByLabel('Supervisor 1', { exact: true })).toHaveValue(
      'audit'
    );
  });

  test('the chosen supervisors are attached to the session when it is saved', async ({
    page,
  }) => {
    const { saves } = await mockNew(page, { templates: [] });
    await page.route('**/api/sessions/s/config', (route) =>
      route.fulfill(
        envelope('/config', {
          sessionId: 's',
          name: 'S',
          description: 'd',
          initiator: 'ui',
          tags: {},
          projectPath: '/srv',
          workflows: {},
        })
      )
    );
    await startBlank(page);
    await page.getByLabel('Name').fill('S');
    await page.getByLabel('Description').fill('d');
    await page.getByLabel('Working directory').fill('/srv');
    await page.getByRole('button', { name: 'Add supervisor' }).click();
    await page
      .getByLabel('Supervisor 1', { exact: true })
      .selectOption('audit');

    await page.getByRole('button', { name: 'Create session' }).click();

    await expect(page).toHaveURL(/\/sessions\/s\/workflow\/main\/edit$/);
    expect(saves[0]).toMatchObject({ assignments: [{ supervisor: 'audit' }] });
  });

  test('Add supervisor is disabled when the server has none to attach', async ({
    page,
  }) => {
    await page.route('**/api/supervisors', (route) =>
      route.fulfill(envelope('/api/supervisors', []))
    );
    await startBlank(page);

    await expect(
      page.getByRole('button', { name: 'Add supervisor' })
    ).toBeDisabled();
    await expect(
      page.getByRole('button', { name: 'Create new' })
    ).toBeVisible();
  });
});

test.describe('New session - the scripted panel', () => {
  test.beforeEach(async ({ page }) => {
    await mockNew(page, { templates: [] });
    await startBlank(page);
  });

  test('shows the same session as MCP, curl and CLI, and updates as you type', async ({
    page,
  }) => {
    await expect(
      page.getByRole('heading', { name: 'Same thing, scripted' })
    ).toBeVisible();
    const mcp = page.getByRole('tab', { name: 'MCP' });
    await expect(mcp).toHaveAttribute('aria-selected', 'true');
    await expect(page.getByText('create_session')).toBeVisible();

    await page.getByLabel('Name').fill('Live Preview');
    await expect(
      page.getByText(/"sessionId": ?"live-preview"/).first()
    ).toBeVisible();

    await page.getByRole('tab', { name: 'curl' }).click();
    await expect(page.getByRole('tab', { name: 'curl' })).toHaveAttribute(
      'aria-selected',
      'true'
    );
    await expect(page.getByText(/curl -X POST/)).toBeVisible();
    await expect(page.getByText('/api/sessions')).toBeVisible();

    await page.getByRole('tab', { name: 'CLI' }).click();
    await expect(page.getByText(/yukta-cli session apply/)).toBeVisible();
  });

  test('the command can be copied', async ({ page, context }) => {
    await context.grantPermissions(['clipboard-read', 'clipboard-write']);
    await page.getByLabel('Name').fill('Copyable');
    await page.getByRole('tab', { name: 'CLI' }).click();

    await page.getByRole('button', { name: 'Copy command' }).click();

    expect(await page.evaluate(() => navigator.clipboard.readText())).toContain(
      'yukta-cli session apply'
    );
  });
});

test.describe('New session - a VIEWER key', () => {
  test('cannot create a session', async ({ page }) => {
    await mockNew(page, { templates: [] });
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
    await startBlank(page);

    const create = page.getByRole('button', { name: 'Create session' });
    await expect(create).toBeDisabled();
    await expect(create).toHaveAttribute('title', /^Needs /);
  });
});
