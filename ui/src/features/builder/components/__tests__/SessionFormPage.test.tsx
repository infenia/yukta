// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { renderWithProviders } from '@/test/utils/testUtils';
import { SessionFormPage, starterSession } from '../SessionFormPage';

const posted: unknown[] = [];
let failure: Response | null = null;

const server = setupServer(
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
  await user.click(screen.getByRole('button', { name: 'Create' }));
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
    expect(screen.getByRole('button', { name: 'Create' })).toBeEnabled();
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
