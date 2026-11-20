// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { Page } from '@playwright/test';
import { test, expect } from '../fixtures';
import { envelope } from '../api-envelope';

const EDITOR = '/sessions/session-1/workflow/build/edit';

const PLUGINS: Record<string, object> = {
  OneShotTrigger: {
    type: 'OneShotTrigger',
    category: 'TRIGGER',
    description: 'Fires once',
    usagePattern: null,
    uiDesign: null,
    outputPorts: [],
    configSchema: [],
  },
  ShellProcessor: {
    type: 'ShellProcessor',
    category: 'PROCESSOR',
    description: 'Runs a shell command',
    usagePattern: 'command: echo hi',
    uiDesign: null,
    outputPorts: ['success', 'failure'],
    configSchema: [
      {
        name: 'command',
        type: 'STRING',
        required: true,
        defaultValue: null,
        description: 'The command line to run',
        options: [],
      },
    ],
  },
  RawProcessor: {
    type: 'RawProcessor',
    category: 'PROCESSOR',
    description: 'Takes raw JSON',
    usagePattern: 'raw: true',
    uiDesign: null,
    outputPorts: [],
    configSchema: [],
  },
  FileSink: {
    type: 'FileSink',
    category: 'TERMINAL',
    description: 'Writes a file',
    usagePattern: null,
    uiDesign: null,
    outputPorts: [],
    configSchema: [],
  },
};

const config = () => ({
  sessionId: 'session-1',
  name: 'Build Pipeline',
  description: '',
  initiator: 'ci',
  tags: {},
  projectPath: '/srv/app',
  workflows: {
    build: {
      workflowId: 'build',
      description: 'Compile and test',
      nodes: [
        { nodeId: 'start', type: 'OneShotTrigger', config: {} },
        {
          nodeId: 'compile',
          type: 'ShellProcessor',
          config: { command: 'make' },
        },
        { nodeId: 'out', type: 'FileSink', config: {} },
      ],
      edges: [
        { source: 'start', target: 'compile', sourcePort: null },
        { source: 'compile', target: 'out', sourcePort: 'success' },
      ],
    },
  },
});

interface Options {
  /** What POST /api/sessions/validate answers. */
  validate?: object;
  validateStatus?: number;
  saveStatus?: number;
  workflows?: Record<string, unknown>;
}

interface Calls {
  validated: Record<string, unknown>[];
  saved: Record<string, unknown>[];
}

async function mockEditor(page: Page, opts: Options = {}): Promise<Calls> {
  const calls: Calls = { validated: [], saved: [] };
  const session = config();
  if (opts.workflows) session.workflows = opts.workflows as never;
  await page.route('**/api/plugins', (r) =>
    r.fulfill(
      envelope(
        '/plugins',
        Object.values(PLUGINS).map((p) => ({
          type: (p as { type: string }).type,
          category: (p as { category: string }).category,
        }))
      )
    )
  );
  await page.route('**/api/plugins/*', (r) => {
    const type = decodeURIComponent(
      new URL(r.request().url()).pathname.split('/').pop() ?? ''
    );
    return PLUGINS[type]
      ? r.fulfill(envelope(`/plugins/${type}`, PLUGINS[type]))
      : r.fulfill({ status: 404, body: '{"message":"none"}' });
  });
  await page.route('**/api/sessions/session-1/config', (r) =>
    r.fulfill(envelope('/config', session))
  );
  await page.route('**/api/sessions/validate', (r) => {
    calls.validated.push(r.request().postDataJSON());
    return opts.validateStatus
      ? r.fulfill({
          status: opts.validateStatus,
          contentType: 'application/json',
          body: '{"message":"validator is down"}',
        })
      : r.fulfill(
          envelope(
            '/validate',
            opts.validate ?? {
              valid: true,
              message: null,
              outputPorts: { compile: ['success', 'failure'] },
            }
          )
        );
  });
  await page.route('**/api/sessions', (r) => {
    if (r.request().method() !== 'POST') return r.fallback();
    calls.saved.push(r.request().postDataJSON());
    return opts.saveStatus
      ? r.fulfill({
          status: opts.saveStatus,
          contentType: 'application/json',
          body: JSON.stringify({
            message: 'The session was changed elsewhere',
          }),
        })
      : r.fulfill(envelope('/sessions', null));
  });
  return calls;
}

const outline = (page: Page) =>
  page.getByRole('complementary', { name: 'Nodes and edges' });
const inspector = (page: Page) =>
  page.getByRole('complementary', { name: 'Node inspector' });
const nodeButton = (page: Page, id: string) =>
  outline(page).getByRole('button', { name: new RegExp(`^${id}`) });
/** Adds a step by searching for its type: Enter picks the first match. */
const addNode = async (page: Page, type: string) => {
  await outline(page).getByRole('button', { name: 'Add step' }).click();
  await page.getByLabel('Search steps').fill(type);
  await page.keyboard.press('Enter');
};

async function open(page: Page, opts: Options = {}) {
  const calls = await mockEditor(page, opts);
  await page.goto(EDITOR);
  await expect(page.getByTestId('workflow-editor')).toBeVisible();
  return calls;
}

test.describe('Workflow editor - loading', () => {
  test('shows the workflow, its description and its nodes with the first selected', async ({
    page,
  }) => {
    await open(page);

    await expect(
      page.getByRole('heading', { level: 1, name: 'Edit build' })
    ).toBeVisible();
    await expect(page.getByLabel('Description', { exact: true })).toHaveValue(
      'Compile and test'
    );
    const tabs = outline(page).getByRole('tablist', { name: 'Outline' });
    await expect(tabs.getByRole('tab', { name: /Nodes\s*3/ })).toHaveAttribute(
      'aria-selected',
      'true'
    );
    await expect(tabs.getByRole('tab', { name: /Edges\s*2/ })).toBeVisible();
    await expect(nodeButton(page, 'start')).toHaveAttribute(
      'aria-current',
      'true'
    );
    await expect(page.getByTestId('workflow-node-compile')).toBeVisible();
  });

  test('a new workflow ID opens an empty editor', async ({ page }) => {
    await mockEditor(page);
    await page.goto('/sessions/session-1/workflow/fresh/edit');

    await expect(
      page.getByRole('heading', { name: 'Edit fresh' })
    ).toBeVisible();
    await expect(
      page.getByText('Select a node to configure it.')
    ).toBeVisible();
    await expect(
      outline(page).getByRole('tab', { name: /Nodes\s*0/ })
    ).toBeVisible();
  });

  test('an empty workflow asks for a trigger, and the one picked starts it', async ({
    page,
  }) => {
    await mockEditor(page);
    await page.goto('/sessions/session-1/workflow/fresh/edit');

    await expect(
      page.getByRole('heading', { name: 'Start with a trigger' })
    ).toBeVisible();
    await page.getByRole('button', { name: 'Pick a trigger' }).click();
    // only triggers are offered
    await expect(page.getByRole('region', { name: 'Terminals' })).toHaveCount(
      0
    );
    await page.getByLabel('Search steps').fill('OneShotTrigger');
    await page.keyboard.press('Enter');

    await expect(nodeButton(page, 'oneshottrigger-1')).toBeVisible();
    await expect(
      page.getByRole('heading', { name: 'Start with a trigger' })
    ).toHaveCount(0);
  });

  test('Undo takes back the last change and Redo brings it back', async ({
    page,
  }) => {
    await open(page);
    await addNode(page, 'ShellProcessor');
    await expect(nodeButton(page, 'shellprocessor-1')).toBeVisible();

    await page.getByRole('button', { name: 'Undo' }).click();
    await expect(nodeButton(page, 'shellprocessor-1')).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Undo' })).toBeDisabled();

    await page.getByRole('button', { name: 'Redo' }).click();
    await expect(nodeButton(page, 'shellprocessor-1')).toBeVisible();
  });

  test('shows a loading state, and an error when the session cannot be loaded', async ({
    page,
  }) => {
    await mockEditor(page);
    await page.route('**/api/sessions/session-1/config', async (r) => {
      await new Promise((resolve) => setTimeout(resolve, 800));
      return r.fulfill(envelope('/config', config()));
    });
    await page.goto(EDITOR);
    await expect(page.getByText('Loading…')).toBeVisible();
    await expect(page.getByTestId('workflow-editor')).toBeVisible();

    await page.route('**/api/sessions/session-1/config', (r) =>
      r.fulfill({
        status: 500,
        contentType: 'application/json',
        body: '{"message":"boom"}',
      })
    );
    await page.goto('/sessions/session-1/workflow/other/edit');
    await expect(page.getByText('Failed to load session')).toBeVisible({
      timeout: 15_000,
    });
  });
});

test.describe('Workflow editor - nodes', () => {
  test('selecting a node shows its inspector, and the Add step picker lists plugins by category', async ({
    page,
  }) => {
    await open(page);

    await nodeButton(page, 'compile').click();
    await expect(nodeButton(page, 'compile')).toHaveAttribute(
      'aria-current',
      'true'
    );
    await expect(inspector(page)).toContainText('ShellProcessor');
    await expect(inspector(page)).toContainText('The command line to run');

    await outline(page).getByRole('button', { name: 'Add step' }).click();
    for (const group of ['Triggers', 'Processors', 'Terminals']) {
      await expect(page.getByRole('region', { name: group })).toBeVisible();
    }
  });

  test('Add step adds the chosen plugin with a unique ID, selects it and connects it after the selected node', async ({
    page,
  }) => {
    await open(page);

    await addNode(page, 'ShellProcessor');
    await expect(nodeButton(page, 'shellprocessor-1')).toHaveAttribute(
      'aria-current',
      'true'
    );

    await addNode(page, 'ShellProcessor');
    await expect(nodeButton(page, 'shellprocessor-2')).toBeVisible();
    await expect(
      page.getByTestId('workflow-node-shellprocessor-2')
    ).toBeVisible();
    await expect(
      outline(page).getByRole('tab', { name: /Nodes\s*5/ })
    ).toBeVisible();
  });

  test('Find node narrows the outline by ID or plugin', async ({ page }) => {
    await open(page);
    const find = outline(page).getByLabel('Find node');

    await find.fill('shell');
    await expect(nodeButton(page, 'compile')).toBeVisible();
    await expect(nodeButton(page, 'start')).toHaveCount(0);

    await find.fill('out');
    await expect(nodeButton(page, 'out')).toBeVisible();
    await expect(nodeButton(page, 'compile')).toHaveCount(0);
  });

  test('Remove node deletes the node and the edges that touched it', async ({
    page,
  }) => {
    await open(page);
    await nodeButton(page, 'compile').click();

    await inspector(page).getByRole('button', { name: 'Remove node' }).click();

    await expect(nodeButton(page, 'compile')).toHaveCount(0);
    await expect(
      outline(page).getByRole('tab', { name: /Nodes\s*2/ })
    ).toBeVisible();
    await expect(
      outline(page).getByRole('tab', { name: /Edges\s*0/ })
    ).toBeVisible();
    await expect(
      page.getByText('Select a node to configure it.')
    ).toBeVisible();
  });
});

test.describe('Workflow editor - the node inspector', () => {
  test.beforeEach(async ({ page }) => {
    await open(page);
    await nodeButton(page, 'compile').click();
  });

  test('a node is renamed, and its retry is set with inputs instead of JSON', async ({
    page,
  }) => {
    await open(page);
    await nodeButton(page, 'compile').click();

    await inspector(page).getByLabel('Node ID').fill('build-step');
    await page.keyboard.press('Enter');
    await expect(nodeButton(page, 'build-step')).toHaveAttribute(
      'aria-current',
      'true'
    );
    await expect(nodeButton(page, 'compile')).toHaveCount(0);

    await inspector(page).getByRole('tab', { name: 'Policy' }).click();
    await inspector(page).getByLabel('Max attempts').fill('3');
    await inspector(page).getByRole('tab', { name: 'JSON' }).click();
    await expect(inspector(page).getByLabel(/The node's config/)).toHaveValue(
      /"maxAttempts": 3/
    );
  });

  test('has Config, Connections, Policy and JSON tabs, opening on Config', async ({
    page,
  }) => {
    const tabs = inspector(page).getByRole('tablist', { name: 'Node' });
    await expect(tabs.getByRole('tab')).toHaveText([
      'Config',
      'Connections',
      'Policy',
      'JSON',
    ]);
    await expect(tabs.getByRole('tab', { name: 'Config' })).toHaveAttribute(
      'aria-selected',
      'true'
    );
  });

  test('Config edits the plugin fields, the stage, the description and the colors', async ({
    page,
  }) => {
    await expect(inspector(page).getByLabel('command')).toHaveValue('make');
    await inspector(page).getByLabel('command').fill('make all');
    await inspector(page).getByLabel('Stage', { exact: true }).fill('build');
    await inspector(page).getByLabel('Node description').fill('Compiles it');
    await inspector(page)
      .getByRole('radiogroup', { name: 'Node color' })
      .getByRole('radio', { name: 'violet' })
      .click();

    await page.getByRole('tab', { name: 'JSON' }).click();
    const json = inspector(page).getByLabel(/The node's config/);
    await expect(json).toHaveValue(/make all/);
    await page.getByRole('tab', { name: 'Config' }).click();
    await expect(
      inspector(page)
        .getByRole('radiogroup', { name: 'Node color' })
        .getByRole('radio', { name: 'violet' })
    ).toHaveAttribute('aria-checked', 'true');
    await expect(page.getByRole('status')).toContainText('Unsaved changes');
  });

  test('Connections lists the edges in and out of the node and removes one', async ({
    page,
  }) => {
    await page.getByRole('tab', { name: 'Connections' }).click();

    await expect(inspector(page)).toContainText('start');
    await expect(inspector(page)).toContainText('out');
    await inspector(page)
      .getByRole('button', { name: 'Remove the edge compile → out' })
      .click();
    await expect(
      inspector(page).getByRole('button', {
        name: 'Remove the edge compile → out',
      })
    ).toHaveCount(0);

    await inspector(page)
      .getByRole('button', { name: 'Add an edge from compile' })
      .click();
    await expect(outline(page).getByLabel('Edge source')).toHaveValue(
      'compile'
    );
  });

  test('Policy shows the engine settings', async ({ page }) => {
    await page.getByRole('tab', { name: 'Policy' }).click();

    await expect(
      inspector(page).getByRole('tabpanel').or(inspector(page)).first()
    ).toBeVisible();
    await expect(inspector(page)).toContainText(/retry|timeout|Retry|Timeout/);
  });

  test('JSON edits the whole config, and invalid JSON blocks Validate and Save', async ({
    page,
  }) => {
    await page.getByRole('tab', { name: 'JSON' }).click();
    const json = inspector(page).getByLabel(/The node's config/);

    await json.fill('{ nope');

    await expect(page.getByRole('button', { name: 'Validate' })).toBeDisabled();
    await expect(
      page.getByRole('button', { name: 'Save version' })
    ).toBeDisabled();
    await expect(
      page.getByText('Fix the JSON field that does not parse')
    ).toBeVisible();

    await json.fill('{"command": "make test"}');
    await expect(page.getByRole('button', { name: 'Validate' })).toBeEnabled();
  });

  test('a plugin without a schema is configured as JSON, with its usage pattern', async ({
    page,
  }) => {
    await addNode(page, 'RawProcessor');

    await expect(
      inspector(page).getByLabel('Configuration (JSON)')
    ).toBeVisible();
    await inspector(page).getByText(/usage/i).first().click();
    await expect(inspector(page)).toContainText('raw: true');
  });
});

test.describe('Workflow editor - edges', () => {
  test.beforeEach(async ({ page }) => {
    await open(page);
    await outline(page).getByRole('tab', { name: /Edges/ }).click();
  });

  test('lists the edges with their ports and removes one', async ({ page }) => {
    const edges = outline(page).getByRole('list', { name: 'Edges' });
    await expect(edges).toContainText('start → compile');
    await expect(edges).toContainText('compile:success → out');

    await outline(page)
      .getByRole('button', { name: 'Remove edge start to compile' })
      .click();

    await expect(edges).not.toContainText('start → compile');
    await expect(
      outline(page).getByRole('tab', { name: /Edges\s*1/ })
    ).toBeVisible();
  });

  test('adds an edge from the form', async ({ page }) => {
    await outline(page).getByLabel('Edge source').selectOption('start');
    await outline(page).getByLabel('Edge target').selectOption('out');
    await outline(page).getByRole('button', { name: 'Add edge' }).click();

    await expect(
      outline(page).getByRole('list', { name: 'Edges' })
    ).toContainText('start → out');
    await expect(
      outline(page).getByRole('tab', { name: /Edges\s*3/ })
    ).toBeVisible();
    await expect(outline(page).getByLabel('Edge source')).toHaveValue('');
  });

  test('refuses an edge from a node to itself and a repeated edge', async ({
    page,
  }) => {
    await outline(page).getByLabel('Edge source').selectOption('compile');
    await outline(page).getByLabel('Edge target').selectOption('compile');
    await outline(page).getByRole('button', { name: 'Add edge' }).click();
    await expect(
      page.getByText('An edge cannot connect a node to itself.')
    ).toBeVisible();

    await outline(page).getByLabel('Edge source').selectOption('start');
    await outline(page).getByLabel('Edge target').selectOption('compile');
    await outline(page).getByRole('button', { name: 'Add edge' }).click();
    await expect(page.getByText('That edge already exists.')).toBeVisible();
    await expect(
      outline(page).getByRole('tab', { name: /Edges\s*2/ })
    ).toBeVisible();
  });

  test('the port list of a node arrives from the check of the draft, with no Validate', async ({
    page,
  }) => {
    await outline(page).getByLabel('Edge source').selectOption('compile');

    // the draft is checked on its own once it has opened
    await expect(page.getByText('Workflow is valid.')).toBeVisible();
    await expect(
      outline(page).getByLabel('Edge port').locator('option')
    ).toHaveText(['no port (default)', 'all ports', 'success', 'failure']);
  });
});

test.describe('Workflow editor - Validate', () => {
  test('a valid workflow says so, and what was sent is the draft', async ({
    page,
  }) => {
    const calls = await open(page);
    await page
      .getByLabel('Description', { exact: true })
      .fill('Changed description');

    await page.getByRole('button', { name: 'Validate' }).click();

    await expect(page.getByText('Workflow is valid.')).toBeVisible();
    expect(calls.validated[calls.validated.length - 1]).toMatchObject({
      workflowId: 'build',
      description: 'Changed description',
    });
  });

  test('an invalid workflow shows the reason', async ({ page }) => {
    await open(page, {
      validate: {
        valid: false,
        message: 'Node out has no incoming edge',
        outputPorts: {},
      },
    });

    await page.getByRole('button', { name: 'Validate' }).click();

    await expect(page.getByRole('alert')).toContainText(
      'Node out has no incoming edge'
    );
    await expect(page.getByText('Workflow is valid.')).toHaveCount(0);
  });

  test('a warning about a port offers Fix it, which starts an edge from that port', async ({
    page,
  }) => {
    await open(page, {
      validate: {
        valid: true,
        message: null,
        outputPorts: { compile: ['success', 'failure'] },
        warnings: [
          {
            nodeId: 'compile',
            field: 'failure',
            message: 'Port failure of compile goes nowhere',
          },
        ],
      },
    });

    await page.getByRole('button', { name: 'Validate' }).click();
    await expect(
      page.getByText('Port failure of compile goes nowhere')
    ).toBeVisible();
    await page.getByRole('button', { name: 'Fix it' }).click();

    await expect(
      outline(page).getByRole('tab', { name: /Edges/ })
    ).toHaveAttribute('aria-selected', 'true');
    await expect(outline(page).getByLabel('Edge source')).toHaveValue(
      'compile'
    );
    await expect(outline(page).getByLabel('Edge port')).toHaveValue('failure');
  });

  test('a failing validate call shows its message', async ({ page }) => {
    await open(page, { validateStatus: 500 });

    await page.getByRole('button', { name: 'Validate' }).click();

    await expect(page.getByRole('alert')).toContainText('validator is down');
  });

  test('the draft is checked on its own: problems are listed, badge their node and lead to it', async ({
    page,
  }) => {
    await open(page, {
      validate: {
        valid: false,
        message: "Node 'out': bad",
        outputPorts: {},
        details: [
          { nodeId: 'out', path: null, code: 'X', message: "Node 'out': bad" },
        ],
      },
    });

    // no Validate click
    const problems = page.getByRole('list', { name: 'Problems' });
    await expect(problems).toContainText("Node 'out': bad");
    await expect(page.getByTestId('node-issues-out')).toBeVisible();

    await problems.getByRole('button', { name: 'Go to out' }).click();
    await expect(nodeButton(page, 'out')).toHaveAttribute(
      'aria-current',
      'true'
    );
  });

  test('an edit is checked again once the draft has rested', async ({
    page,
  }) => {
    const calls = await open(page);
    await expect(page.getByText('Workflow is valid.')).toBeVisible();
    const before = calls.validated.length;

    await page.getByLabel('Description', { exact: true }).fill('edited');

    await expect.poll(() => calls.validated.length).toBeGreaterThan(before);
    expect(calls.validated[calls.validated.length - 1]).toMatchObject({
      description: 'edited',
    });
  });
});

test.describe('Workflow editor - saving', () => {
  test('Save version validates, saves the whole session with the edit and opens the workflow', async ({
    page,
    mockWorkflowApi,
  }) => {
    await mockWorkflowApi(page);
    const calls = await open(page);
    await page
      .getByLabel('Description', { exact: true })
      .fill('New description');
    await addNode(page, 'FileSink');

    await page.getByRole('button', { name: 'Save version' }).click();

    await expect(page).toHaveURL(/\/sessions\/session-1\/workflow\/build$/);
    // the draft is also checked on its own while it is edited; Save checks it last
    expect(calls.validated.length).toBeGreaterThanOrEqual(1);
    expect(calls.validated[calls.validated.length - 1]).toMatchObject({
      description: 'New description',
    });
    expect(calls.saved).toHaveLength(1);
    const saved = calls.saved[0] as ReturnType<typeof config>;
    expect(saved.sessionId).toBe('session-1');
    expect(saved.workflows.build.description).toBe('New description');
    expect(saved.workflows.build.nodes.map((n) => n.nodeId)).toContain(
      'filesink-1'
    );
  });

  test('an invalid workflow is not saved', async ({ page }) => {
    const calls = await open(page, {
      validate: {
        valid: false,
        message: 'Node out has no incoming edge',
        outputPorts: {},
      },
    });

    await page.getByRole('button', { name: 'Save version' }).click();

    await expect(page.getByRole('alert')).toContainText(
      'Node out has no incoming edge'
    );
    expect(calls.saved).toHaveLength(0);
    await expect(page).toHaveURL(/\/edit$/);
  });

  test('a refused save shows why and stays on the editor', async ({ page }) => {
    await open(page, { saveStatus: 409 });

    await page.getByRole('button', { name: 'Save version' }).click();

    await expect(page.getByRole('alert')).toContainText(
      'The session was changed elsewhere'
    );
    await expect(page).toHaveURL(/\/edit$/);
    await expect(
      page.getByRole('button', { name: 'Save version' })
    ).toBeEnabled();
  });

  test('a VIEWER key cannot save', async ({ page }) => {
    await open(page);
    await page.route('**/api/me', (r) =>
      r.fulfill(
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
    await page.reload();

    await expect(
      page.getByRole('button', { name: 'Save version' })
    ).toBeDisabled();
  });
});

test.describe('Workflow editor - unsaved changes', () => {
  test('shows Unsaved changes and Discard returns to the saved workflow', async ({
    page,
  }) => {
    await open(page);
    await expect(page.getByText('Unsaved changes')).toHaveCount(0);

    await page.getByLabel('Description', { exact: true }).fill('edited');
    await expect(
      page.getByRole('status').filter({ hasText: 'Unsaved changes' })
    ).toBeVisible();
    await addNode(page, 'FileSink');

    await page.getByRole('button', { name: 'Discard' }).click();

    await expect(page.getByLabel('Description', { exact: true })).toHaveValue(
      'Compile and test'
    );
    await expect(
      outline(page).getByRole('tab', { name: /Nodes\s*3/ })
    ).toBeVisible();
    await expect(page.getByText('Unsaved changes')).toHaveCount(0);
  });

  test('the edits survive a reload in the same tab, with a note and a way back', async ({
    page,
  }) => {
    await open(page);
    await page
      .getByLabel('Description', { exact: true })
      .fill('kept across reload');

    await page.reload();

    await expect(page.getByLabel('Description', { exact: true })).toHaveValue(
      'kept across reload'
    );
    await expect(
      page.getByText(/Restored your unsaved edits from this browser tab/)
    ).toBeVisible();
    await page.getByRole('button', { name: 'Discard' }).click();
    await expect(page.getByLabel('Description', { exact: true })).toHaveValue(
      'Compile and test'
    );
    await expect(page.getByText(/Restored your unsaved edits/)).toHaveCount(0);
  });

  test('leaving with unsaved changes asks first', async ({ page }) => {
    await open(page);
    await page.getByLabel('Description', { exact: true }).fill('edited');

    await page
      .getByRole('link', { name: 'Sessions', exact: true })
      .first()
      .click();

    await expect(page.getByRole('dialog')).toBeVisible();
  });
});

test.describe('Workflow editor - supervisors', () => {
  test('the Supervisors section counts what the workflow declares and lets one be added', async ({
    page,
  }) => {
    await open(page);
    await page.route('**/api/supervisors', (r) =>
      r.fulfill(
        envelope('/supervisors', [
          {
            id: 'gate',
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
          },
        ])
      )
    );
    await page.reload();

    const summary = page.getByText(/^Supervisors \(0\)/);
    await expect(summary).toBeVisible();
    await summary.click();
    await page.getByRole('button', { name: 'Add supervisor' }).click();

    await expect(page.getByText(/^Supervisors \(1\)/)).toBeVisible();
    await expect(page.getByLabel('Supervisor 1', { exact: true })).toHaveValue(
      'gate'
    );
  });
});
