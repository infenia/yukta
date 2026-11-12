// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import { QueryClientProvider } from '@tanstack/react-query';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { SupervisorsPage } from '../SupervisorsPage';
import { ConflictsBanner } from '../ConflictsBanner';
import { createTestQueryClient } from '@/test/utils/testUtils';

const ok = (data: unknown) =>
  HttpResponse.json({
    timestamp: 't',
    status: 200,
    message: 'ok',
    data,
    path: '/',
  });
const failed = (status = 500) =>
  HttpResponse.json(
    { timestamp: 't', status, message: 'boom', path: '/' },
    { status }
  );

const sup = (id: string, extra: Record<string, unknown> = {}) => ({
  id,
  type: 'rules',
  description: null,
  settings: {},
  dryRun: false,
  system: false,
  attachments: 1,
  applied: 0,
  rejected: 0,
  recorded: 0,
  rateLimited: 0,
  parents: [],
  delegation: {},
  ...extra,
});

let supervisors: unknown[] = [];
let assignments: unknown[] = [];
let mine: unknown[] = [];
const types = [
  { type: 'rules', displayName: 'Rules', category: 'RULES', icon: 'rule' },
  {
    type: 'http',
    displayName: 'External system',
    category: 'EXTERNAL_SYSTEM',
    icon: 'api',
  },
];

const server = setupServer(
  http.get('/api/supervisors', () => ok(supervisors)),
  http.get('/api/supervisors/assignments', () => ok(assignments)),
  http.get('/api/supervisors/:id/assignments', () => ok(mine)),
  http.get('/api/supervisor-engines', () => ok(types)),
  http.get('/api/supervisor-engines/:type', () =>
    ok({
      ...types[0],
      defaultAllowedCommands: null,
      configSchema: [],
      commonSchema: [],
    })
  ),
  http.get('/api/supervision/ownership', ({ request }) => {
    const url = new URL(request.url);
    return ok({
      sessionId: url.searchParams.get('session'),
      workflowId: url.searchParams.get('workflow'),
      defaultSupervisor: true,
      run: 'lead',
      nodes: {
        'build.test': 'agent',
        'deploy.prod': 'guard',
        smoke: 'default',
      },
    });
  }),
  http.get('/api/supervisors/decisions', () => ok([]))
);
beforeAll(() => server.listen());
afterEach(() => {
  server.resetHandlers();
  supervisors = [];
  assignments = [];
  mine = [];
});
afterAll(() => server.close());

function Where() {
  const { search } = useLocation();
  return <output aria-label="where">{search}</output>;
}

const renderAt = (url = '/supervisors') =>
  render(
    <QueryClientProvider client={createTestQueryClient()}>
      <MemoryRouter initialEntries={[url]}>
        <SupervisorsPage />
        <Where />
      </MemoryRouter>
    </QueryClientProvider>
  );

const nav = () => screen.getByRole('navigation', { name: 'Supervisors' });

describe('a supervisor whose type the catalog does not list', () => {
  it('shows the type as text, with no link to open', async () => {
    supervisors = [sup('rules', { type: 'RuleSupervisor', system: true })];
    renderAt();
    const detail = within(await screen.findByRole('article'));
    expect(detail.getAllByText('RuleSupervisor').length).toBeGreaterThan(0);
    expect(
      detail.queryByRole('link', { name: 'RuleSupervisor' })
    ).not.toBeInTheDocument();
  });

  it('opens the first decision maker for an old link to an unknown type', async () => {
    const asked: string[] = [];
    server.use(
      http.get('/api/supervisor-engines/:type', ({ params }) => {
        asked.push(String(params.type));
        return ok({
          ...types[0],
          defaultAllowedCommands: null,
          configSchema: [],
          commonSchema: [],
        });
      })
    );
    renderAt('/supervisors?tab=decision-makers&type=RuleSupervisor');
    await expect.poll(() => asked).toContain('rules');
    expect(asked).not.toContain('RuleSupervisor');
  });
});

describe('the Supervisors tab', () => {
  it('lists the supervisors flat with their engine id, and shows what the chosen one owns', async () => {
    supervisors = [
      sup('lead', { type: 'http', description: 'The release agent.' }),
      sup('ops'),
      sup('guard', { parents: ['lead'], attachments: 2 }),
      sup('agent', { parents: ['lead', 'ops'], dryRun: true }),
    ];
    mine = [
      {
        id: 'a1',
        supervisorId: 'lead',
        scope: 'run:s1/w1',
        enabled: true,
        source: 'MANUAL',
        conflict: false,
      },
      {
        id: 'a2',
        supervisorId: 'lead',
        scope: 'workflow:w2//deploy.*',
        enabled: false,
        source: 'DEFINITION',
        conflict: false,
      },
      {
        id: 'a3',
        supervisorId: 'lead',
        scope: 'workflow:s1/w3',
        enabled: false,
        source: 'SYSTEM',
        conflict: true,
      },
      {
        id: 'a4',
        supervisorId: 'lead',
        scope: 'server',
        enabled: true,
        source: 'OTHER',
        conflict: false,
      },
    ];
    renderAt('/supervisors?supervisor=lead');
    expect(
      await screen.findByRole('heading', { name: 'lead' })
    ).toBeInTheDocument();
    expect(screen.getByText('The release agent.')).toBeInTheDocument();
    // a flat list, alphabetical, with Direct control last
    const rows = within(nav())
      .getAllByRole('button')
      .map((b) => b.textContent);
    expect(rows[0]).toMatch(/^agent/);
    expect(rows[rows.length - 1]).toMatch(/^Direct control/);
    expect(within(nav()).getAllByText('http').length).toBeGreaterThan(0);
    expect(screen.getByText('Live')).toBeInTheDocument();
    // Owns: a run-level target with its nodes, a whole workflow with no session, a conflict, the server
    await expect
      .poll(() => screen.queryAllByText('Run level: lead').length)
      .toBe(2);
    expect(screen.getByRole('link', { name: 's1 / w1' })).toBeInTheDocument();
    expect(
      screen.getByText(/run level · assigned by hand or by an agent/)
    ).toBeInTheDocument();
    expect(
      screen.getByText(/by deploy\.\* · declared in the definition/)
    ).toBeInTheDocument();
    expect(screen.getByText('Disabled')).toBeInTheDocument();
    expect(
      screen.getByText('Disabled: overlaps another owner')
    ).toBeInTheDocument();
    expect(
      within(screen.getByRole('tabpanel', { name: 'Owns' })).getByText(
        'The server'
      )
    ).toBeInTheDocument();
    expect(screen.getAllByText('Direct control').length).toBeGreaterThan(0);
    // the decision maker tab names the one it decides with, and opens the catalog at its type
    await userEvent
      .setup()
      .click(screen.getByRole('tab', { name: 'Decision maker' }));
    expect(
      await screen.findByRole('link', { name: 'Browse decision maker types' })
    ).toHaveAttribute('href', '/supervisors?tab=decision-makers&type=http');
  });

  it('marks dry run, built-in and the nodes it owns; switches tabs and supervisors', async () => {
    const user = userEvent.setup();
    supervisors = [
      sup('lead', { attachments: 1 }),
      sup('guard', {
        parents: ['lead'],
        dryRun: true,
        system: true,
        attachments: 2,
        settings: { allowedCommands: ['PauseWorkflowCommand'] },
        delegation: { APPROVAL: { mode: 'ALL' }, INPUT: { mode: 'WEIRD' } },
      }),
    ];
    assignments = [
      {
        id: 'a1',
        supervisorId: 'guard',
        scope: 'workflow:s1/w1//deploy.*',
        enabled: true,
        source: 'MANUAL',
      },
      {
        id: 'a2',
        supervisorId: 'gone',
        scope: 'workflow:s1/w1',
        enabled: true,
        source: 'MANUAL',
      },
      {
        id: 'a3',
        supervisorId: 'lead',
        scope: 'server',
        enabled: true,
        source: 'MANUAL',
      },
    ];
    mine = [
      {
        id: 'a1',
        supervisorId: 'guard',
        scope: 'workflow:s1/w1//deploy.*',
        enabled: true,
        source: 'MANUAL',
      },
    ];
    renderAt('/supervisors?supervisor=guard');
    expect(
      await screen.findByRole('heading', { name: 'guard' })
    ).toBeInTheDocument();
    expect(
      screen.getByText('Dry run: recorded, not applied')
    ).toBeInTheDocument();
    expect(screen.getByText('Built in (read only)')).toBeInTheDocument();
    expect(screen.getByText('2 targets')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '2 targets' }));
    expect(screen.getByRole('tab', { name: /^Owns/ })).toHaveAttribute(
      'aria-selected',
      'true'
    );
    expect(await screen.findByText('owned here')).toBeInTheDocument();

    await user.click(screen.getByRole('tab', { name: 'Allowed operations' }));
    expect(
      await screen.findByRole('cell', { name: 'Pause and resume' })
    ).toBeInTheDocument();
    expect(screen.getAllByText('Some').length).toBeGreaterThan(0);

    await user.click(screen.getByRole('tab', { name: 'Hierarchy' }));
    expect(
      screen.getByText('Asks all: every one must agree')
    ).toBeInTheDocument();
    expect(screen.getByText('WEIRD')).toBeInTheDocument();
    expect(
      screen.getByText('Asks in order: the first answer decides')
    ).toBeInTheDocument();
    await user.click(
      within(screen.getByRole('tabpanel', { name: 'Hierarchy' })).getByRole(
        'button',
        { name: 'lead' }
      )
    );
    expect(
      await screen.findByRole('heading', { name: 'lead' })
    ).toBeInTheDocument();
    expect(screen.getByText('1 target')).toBeInTheDocument();
    await user.click(screen.getByRole('tab', { name: 'Hierarchy' }));
    expect(
      screen.getByText('nobody; people answer what it asks in the inbox')
    ).toBeInTheDocument();
    await user.click(
      within(screen.getByRole('tabpanel', { name: 'Hierarchy' })).getByRole(
        'button',
        { name: 'guard' }
      )
    );
    expect(screen.getByLabelText('where')).toHaveTextContent(
      'supervisor=guard'
    );

    await user.click(within(nav()).getAllByRole('button', { name: /lead/ })[0]);
    expect(screen.getByLabelText('where')).toHaveTextContent('supervisor=lead');

    await user.type(
      screen.getByRole('searchbox', { name: 'Filter supervisors' }),
      'gua'
    );
    expect(
      within(nav()).queryByRole('button', { name: /lead/ })
    ).not.toBeInTheDocument();
    expect(
      within(nav()).getByRole('button', { name: /guard/ })
    ).toBeInTheDocument();
  });

  it('lists several parents and children, and falls back to the first for an unknown id', async () => {
    const user = userEvent.setup();
    const bare = sup('bare');
    delete (bare as { parents?: unknown }).parents;
    supervisors = [
      bare,
      sup('lead'),
      sup('ops'),
      sup('agent', { parents: ['lead', 'ops'] }),
      sup('guard', { parents: ['lead'] }),
    ];
    server.use(http.get('/api/supervisors/assignments', () => failed()));
    renderAt('/supervisors?supervisor=nope');
    expect(
      await screen.findByRole('heading', { name: 'agent' })
    ).toBeInTheDocument();
    await user.click(screen.getByRole('tab', { name: 'Hierarchy' }));
    expect(
      screen.getByText('Reports to it:', { exact: false })
    ).toBeInTheDocument();
    await user.click(within(nav()).getAllByRole('button', { name: /lead/ })[0]);
    await user.click(screen.getByRole('tab', { name: 'Hierarchy' }));
    const panel = screen.getByRole('tabpanel', { name: 'Hierarchy' });
    expect(
      within(panel).getByRole('button', { name: 'agent' })
    ).toBeInTheDocument();
    expect(
      within(panel).getByRole('button', { name: 'guard' })
    ).toBeInTheDocument();
    await user.click(within(panel).getByRole('button', { name: 'agent' }));
    await user.click(screen.getByRole('tab', { name: 'Hierarchy' }));
    const again = screen.getByRole('tabpanel', { name: 'Hierarchy' });
    expect(
      within(again).getByRole('button', { name: 'lead' })
    ).toBeInTheDocument();
    expect(
      within(again).getByRole('button', { name: 'ops' })
    ).toBeInTheDocument();
  });

  it('says when nothing is owned, and when owners or what it owns cannot be read', async () => {
    supervisors = [sup('lead')];
    renderAt();
    expect(
      await screen.findByText('lead owns nothing yet')
    ).toBeInTheDocument();
  });

  it('shows the loading, failed and empty states of what it owns', async () => {
    supervisors = [sup('lead')];
    server.use(http.get('/api/supervisors/:id/assignments', () => failed()));
    renderAt();
    expect(
      await screen.findByText('Failed to load what it owns.')
    ).toBeInTheDocument();
  });

  it('says when the owners of a workflow cannot be read', async () => {
    supervisors = [sup('lead')];
    mine = [
      {
        id: 'a1',
        supervisorId: 'lead',
        scope: 'workflow:s1/w1',
        enabled: true,
        source: 'MANUAL',
      },
    ];
    server.use(http.get('/api/supervision/ownership', () => failed()));
    renderAt();
    expect(
      await screen.findByText('Could not load the owners of this workflow.')
    ).toBeInTheDocument();
    expect(screen.getByText(/whole workflow/)).toBeInTheDocument();
  });

  it('says when there are no supervisors, and when they cannot be read', async () => {
    renderAt();
    expect(await screen.findByText(/No supervisors yet/)).toBeInTheDocument();
  });

  it('offers a retry when the supervisors cannot be read', async () => {
    const user = userEvent.setup();
    server.use(http.get('/api/supervisors', () => failed()));
    renderAt();
    expect(
      await screen.findByText('Failed to load the supervisors.')
    ).toBeInTheDocument();
    supervisors = [sup('back')];
    server.resetHandlers();
    await user.click(screen.getByRole('button', { name: /Retry/ }));
    expect(
      await screen.findByRole('heading', { name: 'back' })
    ).toBeInTheDocument();
  });

  it('keeps the section in the URL, opens the types sheet, and sends the activity to Control', async () => {
    const user = userEvent.setup();
    supervisors = [sup('lead')];
    renderAt('/supervisors?tab=nope');
    expect(
      await screen.findByRole('heading', { name: 'lead' })
    ).toBeInTheDocument();
    await user.click(
      within(screen.getByRole('tablist', { name: 'lead details' })).getByRole(
        'tab',
        { name: 'Decisions' }
      )
    );
    expect(screen.getByLabelText('where')).toHaveTextContent(
      'section=decisions'
    );
    expect(await screen.findByText('No activity yet')).toBeInTheDocument();
    expect(
      screen.getByRole('link', { name: 'New supervisor' })
    ).toHaveAttribute('href', '/supervisors/new');
    await user.click(
      screen.getByRole('button', { name: 'Decision maker types' })
    );
    expect(screen.getByLabelText('where')).toHaveTextContent(
      'tab=decision-makers'
    );
    expect(
      await screen.findByRole('dialog', { name: 'Decision maker types' })
    ).toBeInTheDocument();
  });

  it('opens the types sheet on an old decision-makers link, falling back to the first for an unknown type', async () => {
    supervisors = [sup('lead')];
    renderAt('/supervisors?tab=decision-makers&type=nope');
    expect(
      await screen.findByRole('dialog', { name: 'Decision maker types' })
    ).toBeInTheDocument();
  });

  it('redirects the old activity tab to the Control page', async () => {
    render(
      <QueryClientProvider client={createTestQueryClient()}>
        <MemoryRouter initialEntries={['/supervisors?tab=activity']}>
          <Routes>
            <Route path="/supervisors" element={<SupervisorsPage />} />
            <Route path="/control" element={<Where />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>
    );
    expect(await screen.findByLabelText('where')).toHaveTextContent(
      '?tab=activity'
    );
  });
});

describe('ConflictsBanner', () => {
  const renderBanner = (props: { session?: string; workflow?: string } = {}) =>
    render(
      <QueryClientProvider client={createTestQueryClient()}>
        <ConflictsBanner {...props} />
      </QueryClientProvider>
    );
  const conflict = (id: string, scope: string) => ({
    id,
    supervisorId: `s-${id}`,
    scope,
    enabled: false,
    source: 'MANUAL',
    conflict: true,
  });

  it('shows nothing without a conflict', async () => {
    assignments = [{ ...conflict('a', 'workflow:s/w'), conflict: false }];
    const { container } = renderBanner();
    await new Promise((r) => setTimeout(r, 50));
    expect(container).toBeEmptyDOMElement();
  });

  it('counts the conflicts, lists them, and keeps one', async () => {
    const user = userEvent.setup();
    assignments = [conflict('a', 'workflow:s/w//x'), conflict('b', 'server')];
    let put: unknown = null;
    server.use(
      http.get('/api/supervisors/s-a/assignments', () => ok([assignments[0]])),
      http.put('/api/supervisors/s-a/assignments', async ({ request }) => {
        put = await request.json();
        return ok([]);
      })
    );
    renderBanner();
    expect(
      await screen.findByText('2 assignments overlap and were disabled.')
    ).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Review' }));
    expect(screen.getByText('s / w · x')).toBeInTheDocument();
    await user.click(
      screen.getAllByRole('button', { name: 'Keep this one' })[0]
    );
    await screen.findByRole('button', { name: 'Hide' });
    await expect
      .poll(() => put)
      .toEqual([{ target: 'workflow:s/w//x', enabled: true, locked: false }]);
    await user.click(screen.getByRole('button', { name: 'Hide' }));
    expect(screen.queryByText('s / w · x')).not.toBeInTheDocument();
  });

  it('shows only the conflicts of a workflow, and why keeping one failed', async () => {
    const user = userEvent.setup();
    assignments = [
      conflict('a', 'workflow:s/w'),
      conflict('b', 'workflow:other/w'),
      conflict('c', 'workflow:w'),
      conflict('d', 'workflow:s/x'),
    ];
    server.use(
      http.get('/api/supervisors/s-a/assignments', () => ok([assignments[0]])),
      http.put('/api/supervisors/s-a/assignments', () => failed(409))
    );
    renderBanner({ session: 's', workflow: 'w' });
    expect(
      await screen.findByText('2 assignments overlap and were disabled.')
    ).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Review' }));
    await user.click(
      screen.getAllByRole('button', { name: 'Keep this one' })[0]
    );
    expect(await screen.findByText('boom')).toBeInTheDocument();
  });

  it('says one assignment in the singular', async () => {
    assignments = [conflict('a', 'server')];
    renderBanner();
    expect(
      await screen.findByText('1 assignment overlaps and was disabled.')
    ).toBeInTheDocument();
  });
});
