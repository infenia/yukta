// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import { QueryClientProvider } from '@tanstack/react-query';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { SupervisorsPage } from '../SupervisorsPage';
import { SupervisorEditPage, NewSupervisorForm } from '../SupervisorEditPage';
import { setMe, type Me } from '@/lib/identity';
import { groupNodes } from '../../lib/scope';
import { getAttachments } from '../../api/supervisors';
import { createTestQueryClient } from '@/test/utils/testUtils';

const envelope = (data: unknown, status = 200) =>
  HttpResponse.json(
    { timestamp: 't', status, message: 'ok', data, path: '/' },
    { status }
  );

const field = (name: string, type = 'STRING', required = false) => ({
  name,
  type,
  required,
  defaultValue: null,
  options: [],
  description: '',
});

const guard = {
  id: 'guard',
  type: 'failure-guard',
  description: 'Stops runs',
  settings: { failures: 3, token: '***' },
  dryRun: false,
  system: false,
  attachments: 2,
  applied: 4,
  rejected: 1,
  recorded: 0,
  rateLimited: 0,
};
const system = { ...guard, id: 'rules', type: 'RuleSupervisor', system: true };

let supervisors: unknown[] = [guard, system];
let attachments: unknown[] = [];
let decisions: unknown[] = [];
let writes: string[] = [];

const server = setupServer(
  http.get('/api/supervisor-engines', () =>
    envelope([
      {
        type: 'failure-guard',
        displayName: 'Failure guard',
        category: 'RULES',
        icon: 'shield',
      },
    ])
  ),
  http.get('/api/supervisor-engines/:type', ({ params }) =>
    envelope({
      type: params.type,
      displayName: 'Failure guard',
      description: 'd',
      usagePattern: '',
      category: 'RULES',
      configSchema: [field('failures', 'NUMBER', true)],
      commonSchema: [field('maxRisk')],
      defaultAllowedCommands: null,
      exampleSettings: {},
    })
  ),
  http.get('/api/supervisors', () => envelope(supervisors)),
  http.get('/api/supervisors/decisions', () => envelope(decisions)),
  http.get('/api/supervisors/assignments', ({ request }) => {
    const source = new URL(request.url).searchParams.get('source');
    return envelope(
      source
        ? (attachments as { source: string }[]).filter(
            (a) => a.source === source
          )
        : attachments
    );
  }),
  http.get('/api/supervisors/:id/assignments', () => envelope(attachments)),
  http.put('/api/supervisors/:id/assignments', async ({ params, request }) => {
    writes.push(`assign ${String(params.id)} ${await request.text()}`);
    return envelope([]);
  }),
  http.post('/api/supervisors', async ({ request }) => {
    writes.push(`create ${await request.text()}`);
    return envelope({}, 201);
  }),
  http.get('/api/supervisors/:id', ({ params }) =>
    params.id === 'rules'
      ? envelope(system)
      : params.id === 'plain'
        ? envelope({ ...guard, id: 'plain', description: null })
        : envelope(guard)
  ),
  http.put('/api/supervisors/:id', async ({ request }) => {
    writes.push(`replace ${await request.text()}`);
    return envelope({});
  }),
  http.delete('/api/supervisors/:id', ({ params }) => {
    writes.push(`delete ${String(params.id)}`);
    return envelope(null);
  }),
  http.get('/api/sessions/summaries', () =>
    envelope([
      { sessionId: 's1', name: 'Alpha' },
      { sessionId: 's2', name: '' },
    ])
  ),
  http.get('/api/sessions/:id/workflows', () =>
    envelope([
      {
        workflowId: 'w1',
        description: '',
        nodeCount: 3,
        edgeCount: 2,
        status: null,
      },
    ])
  ),
  http.get('/api/sessions/:id/workflows/:w', () =>
    envelope({
      nodes: [{ nodeId: 'build.a' }, { nodeId: 'build.b' }, { nodeId: 'end' }],
    })
  )
);
beforeAll(() => server.listen());
afterEach(() => {
  server.resetHandlers();
  supervisors = [guard, system];
  attachments = [];
  decisions = [];
  writes = [];
  setMe(null);
});
afterAll(() => server.close());

function Where() {
  const { pathname, search } = useLocation();
  return <output aria-label="where">{pathname + search}</output>;
}

const renderAt = (url: string) =>
  render(
    <QueryClientProvider client={createTestQueryClient()}>
      <MemoryRouter initialEntries={[url]}>
        <Routes>
          <Route path="/supervisors" element={<SupervisorsPage />} />
          <Route path="/supervisors/new" element={<SupervisorEditPage />} />
          <Route
            path="/supervisors/:supervisorId/edit"
            element={<SupervisorEditPage />}
          />
        </Routes>
        <Where />
      </MemoryRouter>
    </QueryClientProvider>
  );

describe('the edit form', () => {
  const admin = () =>
    setMe({ permissions: { editSessions: true } } as unknown as Me);

  it('creates a supervisor in dry run, with parents, and goes to it', async () => {
    admin();
    const user = userEvent.setup();
    renderAt('/supervisors/new');
    expect(
      screen.getByRole('heading', { name: 'New supervisor' })
    ).toBeInTheDocument();
    const save = screen.getByRole('button', { name: 'Save' });
    expect(save).toBeDisabled();
    await user.type(screen.getByRole('textbox', { name: 'Name' }), 'bad id');
    expect(
      screen.getByText('Use letters, digits, dot, dash or underscore, up to 64')
    ).toBeInTheDocument();
    await user.clear(screen.getByRole('textbox', { name: 'Name' }));
    await user.type(screen.getByRole('textbox', { name: 'Name' }), 'fresh');
    await user.selectOptions(
      await screen.findByRole('combobox', { name: 'Decision maker' }),
      'failure-guard'
    );
    await user.type(
      screen.getByRole('textbox', { name: 'Reports to' }),
      'lead, ops'
    );
    const settings = screen.getByRole('textbox', { name: 'Settings (JSON)' });
    await user.clear(settings);
    await user.type(settings, '[[1]');
    expect(
      screen.getByText('Settings must be a JSON object')
    ).toBeInTheDocument();
    await user.clear(settings);
    await user.type(settings, '{{');
    expect(screen.getByText(/Settings are not valid JSON/)).toBeInTheDocument();
    expect(save).toBeDisabled();
    await user.clear(settings);
    await user.click(save);
    await waitFor(() => expect(writes).toHaveLength(1));
    expect(JSON.parse(writes[0].slice('create '.length))).toEqual({
      id: 'fresh',
      type: 'failure-guard',
      dryRun: true,
      parents: ['lead', 'ops'],
      settings: {},
    });
    expect(await screen.findByLabelText('where')).toHaveTextContent(
      '/supervisors?supervisor=fresh'
    );
  });

  it('edits an existing supervisor, keeps its name, and turns dry run off', async () => {
    admin();
    const user = userEvent.setup();
    renderAt('/supervisors/guard/edit');
    expect(
      await screen.findByRole('heading', { name: 'Edit guard' })
    ).toBeInTheDocument();
    expect(await screen.findByDisplayValue('Stops runs')).toBeInTheDocument();
    expect(screen.getByRole('textbox', { name: 'Name' })).toBeDisabled();
    expect(
      screen.getByText('Changing it replaces the settings below.')
    ).toBeInTheDocument();
    await user.click(screen.getByRole('checkbox'));
    await user.clear(screen.getByRole('textbox', { name: 'Description' }));
    await user.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(writes).toHaveLength(1));
    const sent = JSON.parse(writes[0].slice('replace '.length));
    expect(sent).toMatchObject({ id: 'guard', dryRun: true, parents: [] });
    expect(sent.description).toBeUndefined();
    expect(sent.settings).toEqual({ failures: 3, token: '***' });
  });

  it('shows the server error with its field errors', async () => {
    admin();
    server.use(
      http.put('/api/supervisors/:id', () =>
        HttpResponse.json(
          {
            timestamp: 't',
            status: 400,
            message: 'Invalid settings',
            path: '/',
            errors: [
              { field: 'settings.failures', message: 'must be 1 or more' },
            ],
          },
          { status: 400 }
        )
      )
    );
    const user = userEvent.setup();
    renderAt('/supervisors/plain/edit');
    await screen.findByRole('heading', { name: 'Edit plain' });
    await user.click(await screen.findByRole('button', { name: 'Save' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Invalid settings'
    );
    expect(
      screen.getByText('settings.failures: must be 1 or more')
    ).toBeInTheDocument();
  });

  it('shows an error that comes without a field list', async () => {
    admin();
    server.use(
      http.put('/api/supervisors/:id', () =>
        HttpResponse.json(
          { timestamp: 't', status: 500, message: 'Store is down', path: '/' },
          { status: 500 }
        )
      )
    );
    const user = userEvent.setup();
    renderAt('/supervisors/plain/edit');
    await screen.findByRole('heading', { name: 'Edit plain' });
    await user.click(await screen.findByRole('button', { name: 'Save' }));
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Store is down');
    expect(within(alert).queryByRole('listitem')).toBeNull();
  });

  it('disables save with a saving label until the change is sent', async () => {
    admin();
    let release: () => void = () => undefined;
    const sent = new Promise<void>((resolve) => {
      release = resolve;
    });
    server.use(
      http.put('/api/supervisors/:id', async () => {
        await sent;
        return envelope({});
      })
    );
    const user = userEvent.setup();
    renderAt('/supervisors/plain/edit');
    await screen.findByRole('heading', { name: 'Edit plain' });
    await user.click(await screen.findByRole('button', { name: 'Save' }));
    expect(
      await screen.findByRole('button', { name: 'Saving…' })
    ).toBeDisabled();
    release();
    await waitFor(() =>
      expect(screen.getByLabelText('where')).toHaveTextContent(
        '/supervisors?supervisor=plain'
      )
    );
  });

  it('says when the supervisor cannot be loaded', async () => {
    server.use(
      http.get('/api/supervisors/:id', () =>
        HttpResponse.json({ status: 404, message: 'no' }, { status: 404 })
      )
    );
    renderAt('/supervisors/gone/edit');
    expect(await screen.findByText('Failed to load gone.')).toBeInTheDocument();
  });

  it('creates from the drawer form and hands back the id', async () => {
    admin();
    const user = userEvent.setup();
    let saved = '';
    render(
      <QueryClientProvider client={createTestQueryClient()}>
        <NewSupervisorForm onSaved={(id) => (saved = id)} />
      </QueryClientProvider>
    );
    await user.type(screen.getByRole('textbox', { name: 'Name' }), 'drawer');
    await user.selectOptions(
      await screen.findByRole('combobox', { name: 'Decision maker' }),
      'failure-guard'
    );
    await user.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(saved).toBe('drawer'));
  });
});

describe('small cases', () => {
  it('asks for attachments of a session and workflow by source', async () => {
    let asked = '';
    server.use(
      http.get('/api/supervisors/assignments', ({ request }) => {
        asked = new URL(request.url).search;
        return envelope([]);
      })
    );
    await getAttachments({ session: 's1', workflow: 'w1', source: 'MANUAL' });
    expect(asked).toBe('?session=s1&workflow=w1&source=MANUAL');
  });
});

describe('groupNodes', () => {
  it('groups by the part before the first dot, in order', () => {
    expect(groupNodes(['b.x', 'a', 'b.y'])).toEqual([
      ['b', ['b.x', 'b.y']],
      ['', ['a']],
    ]);
  });
});
