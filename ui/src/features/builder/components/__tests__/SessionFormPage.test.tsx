// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { renderWithProviders } from '@/test/utils/testUtils';
import { SessionFormPage, slugify, starterSession } from '../SessionFormPage';

const posted: unknown[] = [];
let failure: Response | null = null;

const server = setupServer(
  http.post('/api/sessions', async ({ request }) => {
    posted.push(await request.json());
    return (
      failure ??
      HttpResponse.json({ timestamp: 't', status: 200, message: 'ok', data: null, path: '/' })
    );
  })
);

beforeAll(() => server.listen());
afterEach(() => {
  posted.length = 0;
  failure = null;
  window.history.pushState({}, '', '/');
});
afterAll(() => server.close());

async function fillAndSubmit() {
  const user = userEvent.setup();
  renderWithProviders(<SessionFormPage />);
  await user.type(screen.getByLabelText('Session ID'), 'my-session');
  await user.type(screen.getByLabelText('Name'), 'My session');
  await user.type(screen.getByLabelText('Description'), 'desc');
  await user.type(screen.getByLabelText('Project path'), '/work');
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
      expect(window.location.pathname).toBe('/sessions/my-session/workflow/main/edit')
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
    expect(await screen.findByRole('alert')).toHaveTextContent('projectPath: must be absolute');
    expect(screen.getByRole('button', { name: 'Create session' })).toBeEnabled();
  });

  it('shows the message of any other failure', async () => {
    failure = HttpResponse.json({ message: 'Storage down' }, { status: 500 }) as unknown as Response;
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
    expect(screen.getByRole('link', { name: 'Import JSON' })).toHaveAttribute(
      'href',
      '/sessions/import'
    );
    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(window.location.pathname).toBe('/');
    expect(posted).toEqual([]);
  });
});
