// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useLocation } from 'react-router';
import { QueryClientProvider } from '@tanstack/react-query';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { SupervisorsPage } from '../SupervisorsPage';
import { createTestQueryClient } from '@/test/utils/testUtils';

const ok = (data: unknown) =>
  HttpResponse.json({
    timestamp: 't',
    status: 200,
    message: 'ok',
    data,
    path: '/',
  });

const sup = (id: string) => ({
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
});
const att = (
  id: string,
  supervisorId: string,
  scope: string,
  extra: Record<string, unknown> = {}
) => ({
  id,
  supervisorId,
  scope,
  enabled: true,
  source: 'MANUAL',
  originSessionId: null,
  originWorkflowId: null,
  ...extra,
});

let assignments: unknown[] = [];
let summary: unknown = { unowned: 0, unownedWorkflows: [] };
let decisions: unknown[] = [];
let failing = false;

const server = setupServer(
  http.get('/api/supervisors', () => ok([sup('lead'), sup('guard')])),
  http.get('/api/supervisors/assignments', () =>
    failing
      ? HttpResponse.json({ message: 'boom' }, { status: 500 })
      : ok(assignments)
  ),
  http.get('/api/supervisors/:id/assignments', () => ok([])),
  http.get('/api/supervisor-engines', () =>
    ok([
      { type: 'rules', displayName: 'Rules', category: 'RULES', icon: 'rule' },
    ])
  ),
  http.get('/api/supervisors/decisions', () => ok(decisions)),
  http.get('/api/supervision/summary', () => ok(summary)),
  http.get('/api/supervision/ownership', ({ request }) => {
    const url = new URL(request.url);
    return ok({
      sessionId: url.searchParams.get('session'),
      workflowId: url.searchParams.get('workflow'),
      defaultSupervisor: true,
      run: 'lead',
      nodes: { build: 'lead' },
    });
  }),
  http.get('/api/supervision', () => ok({}))
);
beforeAll(() => server.listen());
afterEach(() => {
  server.resetHandlers();
  assignments = [];
  summary = { unowned: 0, unownedWorkflows: [] };
  decisions = [];
  failing = false;
});
afterAll(() => server.close());

function Where() {
  const { search } = useLocation();
  return <output aria-label="where">{search}</output>;
}
const renderAt = (url = '/supervisors?view=workflows') =>
  render(
    <QueryClientProvider client={createTestQueryClient()}>
      <MemoryRouter initialEntries={[url]}>
        <SupervisorsPage />
        <Where />
      </MemoryRouter>
    </QueryClientProvider>
  );
const nav = () => screen.findByRole('navigation', { name: 'Workflows' });

describe('the By workflow view', () => {
  it('lists the workflows with their controllers, and shows the chosen one', async () => {
    const user = userEvent.setup();
    assignments = [
      att('1', 'lead', 'workflow:s/w'),
      att('2', 'guard', 'workflow:s/w//deploy.*'),
      att('3', 'lead', 'workflow:s/other', { enabled: false }),
    ];
    decisions = [
      {
        time: new Date(Date.now() - 300_000).toISOString(),
        supervisorId: 'lead',
        outcome: 'applied',
        command: 'PauseWorkflowCommand',
        executionId: 'e1',
        reason: null,
        workflowId: 'w',
      },
    ];
    renderAt();
    const list = await nav();
    // a workflow with something switched off comes first, and is chosen
    const rows = await within(list).findAllByRole('button');
    expect(rows[0]).toHaveTextContent('s / other');
    expect(rows[0]).toHaveTextContent('Disabled');
    expect(rows[1]).toHaveTextContent('s / w');
    expect(rows[1]).toHaveTextContent('lead +1 more');
    expect(rows[1]).toHaveTextContent('1');
    expect(rows[0]).toHaveAttribute('aria-current', 'true');
    await user.click(rows[1]);
    expect(screen.getByLabelText('where')).toHaveTextContent(
      'session=s&workflow=w'
    );
    expect(
      await screen.findByRole('heading', { name: 's / w', level: 2 })
    ).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Open workflow' })).toHaveAttribute(
      'href',
      '/sessions/s/workflow/w?supervision=1'
    );
    expect(
      await screen.findByText('Actions taken on this workflow')
    ).toBeInTheDocument();
    const assigned = screen.getByRole('region', { name: 'Assignments' });
    expect(assigned).toHaveTextContent('2');
    expect(
      await within(assigned).findAllByRole('link', { name: 'lead' })
    ).not.toHaveLength(0);
    expect(
      within(assigned).getByRole('link', { name: 'guard' })
    ).toHaveAttribute('href', '/supervisors?supervisor=guard');
  });

  it('lists workflows nobody owns under Direct control, and says when the list is capped', async () => {
    summary = {
      unowned: 150,
      unownedWorkflows: [{ sessionId: 'u', workflowId: 'free' }],
    };
    renderAt('/supervisors?view=workflows&session=u&workflow=free');
    const list = await nav();
    expect(await within(list).findByText('Direct control')).toBeInTheDocument();
    expect(
      within(list).getByText('Showing 1 of 150 workflows nobody owns.')
    ).toBeInTheDocument();
    expect(
      await screen.findByText(
        'No supervisor is assigned: people and agents control it directly.'
      )
    ).toBeInTheDocument();
  });

  it('filters by name and by controller, and clears the filters', async () => {
    const user = userEvent.setup();
    assignments = [
      att('1', 'lead', 'workflow:s/alpha'),
      att('2', 'guard', 'workflow:s/beta'),
    ];
    summary = {
      unowned: 1,
      unownedWorkflows: [{ sessionId: 's', workflowId: 'free' }],
    };
    renderAt();
    const list = await nav();
    await within(list).findAllByRole('button');
    const count = () => within(list).getAllByRole('button').length;
    expect(count()).toBe(3);
    await user.type(screen.getByLabelText('Filter workflows'), 'bet');
    expect(count()).toBe(1);
    await user.clear(screen.getByLabelText('Filter workflows'));
    const pick = async (option: string) => {
      await user.click(screen.getByLabelText('Filter by controller'));
      await user.click(await screen.findByRole('option', { name: option }));
      await user.keyboard('{Escape}');
    };
    await pick('lead');
    expect(count()).toBe(1);
    await pick('Direct control');
    expect(count()).toBe(2);
    await user.type(screen.getByLabelText('Filter workflows'), 'zzz');
    expect(screen.getByText('No workflow matches')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Clear filters' }));
    expect(count()).toBe(3);
  });

  it('still lists the workflows when the decisions cannot be loaded', async () => {
    server.use(
      http.get('/api/supervisors/decisions', () =>
        HttpResponse.json({ message: 'boom' }, { status: 500 })
      )
    );
    assignments = [att('1', 'lead', 'workflow:s/w')];
    renderAt();
    const list = await nav();
    const rows = await within(list).findAllByRole('button');
    expect(rows).toHaveLength(1);
    expect(rows[0]).toHaveTextContent('s / w');
  });

  it('lists under a chosen workflow only the assignments that name it', async () => {
    assignments = [
      att('1', 'lead', 'workflow:s/alpha'),
      att('2', 'audit', 'global'),
      att('3', 'ops', 'session:s'),
    ];
    renderAt('/supervisors?view=workflows&session=s&workflow=alpha');
    const section = await screen.findByRole('region', { name: 'Assignments' });
    expect(within(section).getByText('1')).toBeInTheDocument();
    expect(within(section).queryByText(/audit/)).not.toBeInTheDocument();
    expect(within(section).queryByText(/ops/)).not.toBeInTheDocument();
  });

  it('still offers Direct control in the controller filter when the supervisors cannot be loaded', async () => {
    const user = userEvent.setup();
    server.use(
      http.get('/api/supervisors', () =>
        HttpResponse.json({ message: 'boom' }, { status: 500 })
      )
    );
    assignments = [att('1', 'lead', 'workflow:s/alpha')];
    renderAt();
    const list = await nav();
    await within(list).findAllByRole('button');
    await user.click(screen.getByLabelText('Filter by controller'));
    expect(
      await screen.findByRole('option', { name: 'Direct control' })
    ).toBeInTheDocument();
    await user.keyboard('{Escape}');
  });

  it('opens a workflow named in the URL that nobody listed, and one with no session', async () => {
    assignments = [att('1', 'lead', 'workflow:everywhere')];
    renderAt('/supervisors?view=workflows&session=x&workflow=unlisted');
    expect(
      await screen.findByRole('heading', { name: 'x / unlisted', level: 2 })
    ).toBeInTheDocument();
    const user = userEvent.setup();
    const list = await nav();
    await user.click(
      await within(list).findByRole('button', { name: /everywhere/ })
    );
    expect(
      await screen.findByText('Applies in every session')
    ).toBeInTheDocument();
    expect(screen.getByLabelText('where')).not.toHaveTextContent('session=');
    expect(screen.queryByRole('link', { name: 'Open workflow' })).toBeNull();
  });

  it('shows the assignments across sessions, each linking to its supervisor', async () => {
    assignments = [
      att('1', 'lead', 'session:release'),
      att('2', 'guard', 'global'),
    ];
    renderAt();
    const list = await nav();
    expect(
      await within(list).findByRole('link', { name: 'Session release · lead' })
    ).toHaveAttribute('href', '/supervisors?supervisor=lead');
    expect(await screen.findByText('Pick a workflow')).toBeInTheDocument();
  });

  it('says when there are no workflows, and when they cannot be loaded, with retry', async () => {
    const user = userEvent.setup();
    const first = renderAt();
    expect(await screen.findByText('No workflows yet')).toBeInTheDocument();
    first.unmount();
    failing = true;
    renderAt();
    expect(
      await screen.findByText('Failed to load the workflows.')
    ).toBeInTheDocument();
    failing = false;
    assignments = [att('1', 'lead', 'workflow:s/w')];
    await user.click(screen.getByRole('button', { name: /Retry/ }));
    await waitFor(() =>
      expect(screen.queryByText('Failed to load the workflows.')).toBeNull()
    );
    expect(await nav()).toBeInTheDocument();
  });

  it('folds the list on a phone once a workflow is chosen', async () => {
    const user = userEvent.setup();
    assignments = [
      att('1', 'lead', 'workflow:s/a'),
      att('2', 'lead', 'workflow:s/b'),
    ];
    renderAt('/supervisors?view=workflows&session=s&workflow=a');
    const list = await nav();
    expect(list).toHaveClass('max-md:hidden');
    await user.click(
      screen.getByRole('button', { name: /All workflows \(2\)/ })
    );
    expect(list).not.toHaveClass('max-md:hidden');
    await user.click(within(list).getByRole('button', { name: /s \/ b/ }));
    expect(list).toHaveClass('max-md:hidden');
    expect(
      await screen.findByRole('heading', { name: 's / b', level: 2 })
    ).toHaveFocus();
  });

  it('is reached from the views switch', async () => {
    const user = userEvent.setup();
    assignments = [att('1', 'lead', 'workflow:s/w')];
    renderAt('/supervisors?supervisor=lead');
    const views = await screen.findByRole('navigation', {
      name: 'Supervision views',
    });
    await user.click(within(views).getByRole('link', { name: /By workflow/ }));
    expect(screen.getByLabelText('where')).toHaveTextContent('view=workflows');
    expect(await nav()).toBeInTheDocument();
  });
});
