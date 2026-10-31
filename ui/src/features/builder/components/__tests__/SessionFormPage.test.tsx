// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { renderWithProviders } from '@/test/utils/testUtils';
import { SessionFormPage } from '../SessionFormPage';
import { slugify, starterSession } from '../SessionFormPage.helpers';

const posted: unknown[] = [];
let failure: Response | null = null;
let found: { exists: boolean; directory: boolean } | null = {
  exists: true,
  directory: true,
};
const checked: unknown[] = [];

const server = setupServer(
  http.get('/api/templates', () =>
    HttpResponse.json({
      timestamp: 't',
      status: 200,
      message: 'ok',
      data: [
        {
          id: '01-hello-world',
          title: 'Hello world',
          summary: 'Smallest workflow',
          pluginTypes: ['CONSOLE_TERMINAL', 'CONSTANT_SOURCE'],
        },
      ],
      path: '/',
    })
  ),
  http.get('/api/plugins', () =>
    HttpResponse.json({
      timestamp: 't',
      status: 200,
      message: 'ok',
      data: [
        { type: 'CONSTANT_SOURCE', category: 'TRIGGER' },
        { type: 'CONSOLE_TERMINAL', category: 'TERMINAL' },
      ],
      path: '/',
    })
  ),
  http.get('/api/templates/01-hello-world', () =>
    HttpResponse.json({
      timestamp: 't',
      status: 200,
      message: 'ok',
      data: {
        id: '01-hello-world',
        title: 'Hello world',
        summary: 'Smallest workflow',
        pluginTypes: ['CONSOLE_TERMINAL', 'CONSTANT_SOURCE'],
        readme: '# 01 — Hello world\n<b>not html</b>',
        session: {
          sessionId: 'example-01-hello',
          name: 'Hello world',
          description: 'Smallest workflow',
          initiator: 'examples',
          projectPath: '/tmp',
          tags: { example: '01' },
          workflows: {
            hello: {
              workflowId: 'hello',
              description: 'Echo',
              nodes: [{ nodeId: 'start', type: 'CONSTANT_SOURCE', config: {} }],
              edges: [],
            },
            second: {
              workflowId: 'second',
              description: 'Another',
              nodes: [
                { nodeId: 'a', type: 'CONSTANT_SOURCE', config: {} },
                { nodeId: 'b', type: 'CONSOLE_TERMINAL', config: {} },
              ],
              edges: [{ source: 'a', target: 'b', sourcePort: null }],
            },
          },
        },
      },
      path: '/',
    })
  ),
  http.post('/api/sessions/validate/project-path', async ({ request }) => {
    checked.push(await request.json());
    return found
      ? HttpResponse.json({
          timestamp: 't',
          status: 200,
          message: 'ok',
          data: found,
          path: '/',
        })
      : HttpResponse.json({ message: 'Forbidden' }, { status: 403 });
  }),
  http.post('/api/sessions', async ({ request }) => {
    posted.push(await request.json());
    return (
      failure ??
      HttpResponse.json({
        timestamp: 't',
        status: 200,
        message: 'ok',
        data: null,
        path: '/',
      })
    );
  })
);

beforeAll(() => server.listen());
afterEach(() => {
  server.resetHandlers();
  posted.length = 0;
  failure = null;
  found = { exists: true, directory: true };
  checked.length = 0;
  window.history.pushState({}, '', '/');
});
afterAll(() => server.close());

async function fillAndSubmit() {
  const user = userEvent.setup();
  renderWithProviders(<SessionFormPage />);
  await user.click(await screen.findByRole('button', { name: 'Start blank' }));
  await user.type(screen.getByLabelText('Session ID'), 'my-session');
  await user.type(screen.getByLabelText('Name'), 'My session');
  await user.type(screen.getByLabelText('Description'), 'desc');
  await user.type(screen.getByLabelText('Working directory'), '/work');
  await user.click(screen.getByRole('button', { name: 'Create session' }));
}

describe('starterSession', () => {
  it('wires a trigger to a terminal in a workflow called main', () => {
    const session = starterSession({
      sessionId: 's',
      name: 'n',
      description: 'd',
      initiator: 'i',
      projectPath: '/p',
    });
    expect(session.workflows.main.nodes.map((n) => n.type)).toEqual([
      'API_TRIGGER',
      'CONSOLE_TERMINAL',
    ]);
    expect(session.workflows.main.edges).toEqual([
      { source: 'start', target: 'out', sourcePort: null },
    ]);
    expect(session.tags).toEqual({});
  });
});

describe('SessionFormPage', () => {
  async function leavePath(path: string) {
    const user = userEvent.setup();
    renderWithProviders(<SessionFormPage />);
    await user.click(
      await screen.findByRole('button', { name: 'Start blank' })
    );
    const input = screen.getByLabelText('Working directory');
    await user.type(input, path);
    await user.tab();
    return { user, input };
  }

  it("says when the server can't find the working directory, until the path changes", async () => {
    found = { exists: false, directory: false };
    const { user, input } = await leavePath(' /srv/repos/payments ');
    expect(await screen.findByText(/The server can't find/)).toHaveTextContent(
      "The server can't find /srv/repos/payments. Use an absolute path on the server, not on your machine."
    );
    expect(input).toHaveAttribute('aria-invalid', 'true');
    expect(checked).toEqual([{ projectPath: '/srv/repos/payments' }]);

    await user.type(input, 'x');
    expect(screen.queryByText(/The server can't find/)).not.toBeInTheDocument();
    expect(
      screen.getByText('Absolute path on the server, not on your machine')
    ).toBeInTheDocument();
  });

  it('says when the working directory is a file', async () => {
    found = { exists: true, directory: false };
    await leavePath('/etc/hosts');
    expect(
      await screen.findByText(/is a file, not a directory/)
    ).toHaveTextContent('/etc/hosts is a file, not a directory.');
  });

  it('keeps the hint for a directory, and when the lookup is not allowed', async () => {
    const { user, input } = await leavePath('/work');
    await waitFor(() => expect(checked).toHaveLength(1));
    found = null;
    await user.type(input, '2');
    await user.tab();
    await waitFor(() => expect(checked).toHaveLength(2));
    expect(input).not.toHaveAttribute('aria-invalid');
    expect(
      screen.getByText('Absolute path on the server, not on your machine')
    ).toBeInTheDocument();
  });

  it('does not look up an empty working directory', async () => {
    await leavePath(' ');
    expect(checked).toEqual([]);
  });

  it('creates the session with the starter workflow and opens its editor', async () => {
    await fillAndSubmit();
    await waitFor(() => expect(posted).toHaveLength(1));
    expect(posted[0]).toMatchObject({
      sessionId: 'my-session',
      initiator: 'ui',
      projectPath: '/work',
      workflows: { main: { workflowId: 'main' } },
    });
    await waitFor(() =>
      expect(window.location.pathname).toBe(
        '/sessions/my-session/workflow/main/edit'
      )
    );
  });

  it('lists the field errors the server reports', async () => {
    failure = HttpResponse.json(
      {
        message: 'Validation failed',
        errors: [{ field: 'projectPath', message: 'must be absolute' }],
      },
      { status: 400 }
    ) as unknown as Response;
    await fillAndSubmit();
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'projectPath: must be absolute'
    );
    expect(
      screen.getByRole('button', { name: 'Create session' })
    ).toBeEnabled();
  });

  it('shows the message of any other failure', async () => {
    failure = HttpResponse.json(
      { message: 'Storage down' },
      { status: 500 }
    ) as unknown as Response;
    await fillAndSubmit();
    expect(await screen.findByRole('alert')).toHaveTextContent('Storage down');
  });
});

describe('slugify', () => {
  it('lower-cases and joins every run of other characters with one dash', () => {
    expect(slugify('  Payments service / v2! ')).toBe('payments-service-v2');
    expect(slugify('***')).toBe('');
  });
});

describe('SessionFormPage, new', () => {
  it('makes the session ID from the name until the ID is edited', async () => {
    const user = userEvent.setup();
    renderWithProviders(<SessionFormPage />);
    await user.click(
      await screen.findByRole('button', { name: 'Start blank' })
    );
    await user.type(screen.getByLabelText('Name'), 'Payments service');
    expect(screen.getByLabelText('Session ID')).toHaveValue('payments-service');

    await user.type(screen.getByLabelText('Session ID'), '-x');
    await user.type(screen.getByLabelText('Name'), ' 2');
    expect(screen.getByLabelText('Session ID')).toHaveValue(
      'payments-service-x'
    );
  });

  it('shows the same session as an MCP call, curl and the CLI, as you type', async () => {
    const user = userEvent.setup();
    renderWithProviders(<SessionFormPage />);
    await user.click(
      await screen.findByRole('button', { name: 'Start blank' })
    );
    await user.type(screen.getByLabelText('Name'), 'Pay');
    const panel = screen.getByRole('region', { name: 'Same thing, scripted' });
    const code = () =>
      within(panel).getByText((_, node) => node?.tagName === 'PRE')
        .textContent ?? '';
    const mcp = JSON.parse(code());
    expect(mcp.name).toBe('create_session');
    expect(mcp.arguments.config).toMatchObject({
      sessionId: 'pay',
      name: 'Pay',
      initiator: 'ui',
      tags: {},
    });
    expect(mcp.arguments.config.workflows.main.workflowId).toBe('main');

    await user.click(within(panel).getByRole('tab', { name: 'curl' }));
    expect(code()).toMatch(
      /^curl -X POST http:\/\/localhost:\d+\/api\/sessions /
    );
    await user.click(within(panel).getByRole('tab', { name: 'CLI' }));
    expect(code()).toMatch(/^yukta-cli session apply '\{"sessionId":"pay"/);
  });

  it('links to the JSON import, and Cancel goes home without saving', async () => {
    const user = userEvent.setup();
    window.history.pushState({}, '', '/sessions/new');
    renderWithProviders(<SessionFormPage />);
    await user.click(
      await screen.findByRole('button', { name: 'Start blank' })
    );
    expect(screen.getByRole('link', { name: 'Import JSON' })).toHaveAttribute(
      'href',
      '/sessions/import'
    );
    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(window.location.pathname).toBe('/sessions');
    expect(posted).toEqual([]);
  });

  it('creates a session from a template with its workflows, name and a suggested ID', async () => {
    const user = userEvent.setup();
    renderWithProviders(<SessionFormPage />);
    await user.click(
      await screen.findByRole('button', { name: 'Use Hello world' })
    );
    expect(await screen.findByLabelText('Session ID')).toHaveValue(
      '01-hello-world-1'
    );
    expect(screen.getByLabelText('Name')).toHaveValue('Hello world');
    await user.clear(screen.getByLabelText('Working directory'));
    await user.type(screen.getByLabelText('Working directory'), '/work');
    await user.click(screen.getByRole('button', { name: 'Create session' }));
    await waitFor(() => expect(posted).toHaveLength(1));
    expect(posted[0]).toMatchObject({
      sessionId: '01-hello-world-1',
      name: 'Hello world',
      tags: { example: '01' },
      workflows: { hello: { nodes: [{ nodeId: 'start' }] } },
    });
  });

  it('previews the README as text and uses the template from the preview', async () => {
    const user = userEvent.setup();
    renderWithProviders(<SessionFormPage />);
    await user.click(await screen.findByRole('button', { name: 'Preview' }));
    const dialog = await screen.findByRole('dialog', { name: 'Hello world' });
    // the README is Markdown (a heading), and markup in it stays text
    expect(
      within(dialog).getByRole('heading', { name: '01 — Hello world' })
    ).toBeInTheDocument();
    expect(within(dialog).getByText(/<b>not html<\/b>/)).toBeInTheDocument();
    await user.click(
      within(dialog).getByRole('button', { name: 'Use this template' })
    );
    expect(await screen.findByLabelText('Name')).toHaveValue('Hello world');
  });

  it('shows the workflow on a canvas, with a tab for each workflow', async () => {
    const user = userEvent.setup();
    renderWithProviders(<SessionFormPage />);
    await user.click(await screen.findByRole('button', { name: 'Preview' }));
    const dialog = await screen.findByRole('dialog', { name: 'Hello world' });

    expect(within(dialog).getByTestId('template-canvas')).toBeInTheDocument();
    const tabs = within(dialog).getAllByRole('tab');
    expect(tabs.map((tab) => tab.textContent)).toEqual(['hello', 'second']);
    expect(tabs[0]).toHaveAttribute('aria-selected', 'true');
    await user.click(tabs[1]);
    expect(tabs[1]).toHaveAttribute('aria-selected', 'true');
  });

  it('previews a template without a canvas when it has no workflow, and without tabs for one', async () => {
    const template = (workflows: Record<string, unknown>) =>
      HttpResponse.json({
        timestamp: 't',
        status: 200,
        message: 'ok',
        data: {
          id: '01-hello-world',
          title: 'Hello world',
          summary: 's',
          pluginTypes: ['MYSTERY'],
          readme: 'text',
          session: {
            sessionId: 'x',
            name: 'Hello world',
            description: 's',
            initiator: 'examples',
            projectPath: '/tmp',
            tags: {},
            workflows,
          },
        },
        path: '/',
      });
    const user = userEvent.setup();
    server.use(
      http.get('/api/templates', () =>
        HttpResponse.json({
          timestamp: 't',
          status: 200,
          message: 'ok',
          data: [
            {
              id: '01-hello-world',
              title: 'Hello world',
              summary: 's',
              pluginTypes: ['MYSTERY'],
            },
          ],
          path: '/',
        })
      ),
      http.get('/api/templates/01-hello-world', () => template({}))
    );
    renderWithProviders(<SessionFormPage />);
    await user.click(await screen.findByRole('button', { name: 'Preview' }));
    const empty = await screen.findByRole('dialog', { name: 'Hello world' });
    expect(within(empty).queryByTestId('template-canvas')).toBeNull();
    // a type no plugin is registered for gets the neutral colour
    expect(screen.getByText('MYSTERY').getAttribute('style')).toContain(
      '--color-outline'
    );
    await user.click(within(empty).getByRole('button', { name: 'Close' }));

    server.use(
      http.get('/api/templates/01-hello-world', () =>
        template({
          only: { workflowId: 'only', description: '', nodes: [], edges: [] },
        })
      )
    );
    await user.click(screen.getByRole('button', { name: 'Preview' }));
    const one = await screen.findByRole('dialog', { name: 'Hello world' });
    expect(await within(one).findByTestId('template-canvas')).toBeVisible();
    expect(within(one).queryByRole('tab')).toBeNull();
  });

  it('colours the plugin chips by category', async () => {
    renderWithProviders(<SessionFormPage />);
    const chip = await screen.findByText('CONSOLE_TERMINAL');
    await waitFor(() =>
      expect(chip.getAttribute('style')).toContain('--color-cat-terminal')
    );
    expect(screen.getByText('CONSTANT_SOURCE').getAttribute('style')).toContain(
      '--color-cat-trigger'
    );
  });

  it('closes the preview without choosing', async () => {
    const user = userEvent.setup();
    renderWithProviders(<SessionFormPage />);
    await user.click(await screen.findByRole('button', { name: 'Preview' }));
    await user.click(await screen.findByRole('button', { name: 'Close' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Preview' }));
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('shows an error when a template cannot be fetched', async () => {
    server.use(
      http.get('/api/templates/01-hello-world', () =>
        HttpResponse.json({ message: 'gone' }, { status: 404 })
      )
    );
    const user = userEvent.setup();
    renderWithProviders(<SessionFormPage />);
    await user.click(
      await screen.findByRole('button', { name: 'Use Hello world' })
    );
    expect(await screen.findByRole('alert')).toBeInTheDocument();
  });

  it('still offers a blank start when the templates fail to load', async () => {
    server.use(
      http.get('/api/templates', () =>
        HttpResponse.json({ message: 'down' }, { status: 500 })
      )
    );
    renderWithProviders(<SessionFormPage />);
    expect(
      await screen.findByRole('button', { name: 'Start blank' })
    ).toBeInTheDocument();
  });
});
