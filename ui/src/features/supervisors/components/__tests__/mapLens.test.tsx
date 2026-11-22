// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
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
let failing = false;
let ownershipAsked: string[] = [];

const server = setupServer(
  http.get('/api/supervisors', () => ok(supervisors)),
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
  http.get('/api/supervisors/decisions', () => ok([])),
  http.get('/api/supervision/summary', () =>
    ok({
      unowned: 1,
      unownedWorkflows: [{ sessionId: 'u', workflowId: 'free' }],
    })
  ),
  http.get('/api/supervision/ownership', ({ request }) => {
    const url = new URL(request.url);
    ownershipAsked.push(
      `${url.searchParams.get('session')}/${url.searchParams.get('workflow')}`
    );
    return ok({
      sessionId: url.searchParams.get('session'),
      workflowId: url.searchParams.get('workflow'),
      defaultSupervisor: true,
      run: 'lead',
      nodes: { build: 'lead', ship: 'default' },
    });
  })
);
beforeAll(() => server.listen());
afterEach(() => {
  server.resetHandlers();
  supervisors = [];
  assignments = [];
  failing = false;
  ownershipAsked = [];
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
        <SupervisorsPage />
        <Where />
      </MemoryRouter>
    </QueryClientProvider>
  );

const overview = () =>
  screen.findByRole('region', { name: 'Supervision overview' });
const box = async (id: string) => {
  const map = await overview();
  await waitFor(() =>
    expect(map.querySelector(`[data-id="${id}"]`)).not.toBeNull()
  );
  return map.querySelector(`[data-id="${id}"]`) as HTMLElement;
};

describe('the supervision overview', () => {
  it('is reached from the views switch and goes back to the list', async () => {
    const user = userEvent.setup();
    supervisors = [sup('lead')];
    renderAt('/supervisors?supervisor=lead&section=owns');
    const views = await screen.findByRole('navigation', {
      name: 'Supervision views',
    });
    expect(
      within(views).getByRole('link', { name: /By supervisor/ })
    ).toHaveAttribute('aria-current', 'page');
    await user.click(within(views).getByRole('link', { name: /Map/ }));
    expect(screen.getByLabelText('where')).toHaveTextContent('view=map');
    expect(screen.getByLabelText('where')).not.toHaveTextContent('supervisor=');
    expect(await overview()).toBeInTheDocument();
    expect(
      screen.queryByRole('navigation', { name: 'Supervisors' })
    ).not.toBeInTheDocument();
    await user.click(
      within(views).getByRole('link', { name: /By supervisor/ })
    );
    expect(screen.getByLabelText('where')).not.toHaveTextContent('view=map');
    expect(
      await screen.findByRole('navigation', { name: 'Supervisors' })
    ).toBeInTheDocument();
  });

  it('draws the supervisors, their workflows and Direct control, and opens a supervisor on a click', async () => {
    supervisors = [
      sup('lead'),
      sup('guard', { parents: ['lead'], dryRun: true }),
    ];
    assignments = [
      {
        id: 'a1',
        supervisorId: 'lead',
        scope: 'workflow:s/w',
        enabled: true,
        source: 'MANUAL',
      },
    ];
    renderAt('/supervisors?view=map');
    expect(await box('supervisor:guard')).toHaveTextContent('Dry run');
    expect(await box('workflow:s/w')).toHaveTextContent('s');
    expect(await box('direct')).toHaveTextContent('1 workflow');
    expect(await box('workflow:u/free')).toBeInTheDocument();
    fireEvent.click(await box('supervisor:guard'));
    await waitFor(() =>
      expect(screen.getByLabelText('where')).toHaveTextContent(
        'supervisor=guard'
      )
    );
  });

  it('opens and closes a workflow to show its nodes with their owners', async () => {
    supervisors = [sup('lead')];
    assignments = [
      {
        id: 'a1',
        supervisorId: 'lead',
        scope: 'workflow:s/w',
        enabled: true,
        source: 'MANUAL',
      },
    ];
    renderAt('/supervisors?view=map');
    fireEvent.click(await box('workflow:s/w'));
    expect(await box('workflow:s/w#build')).toHaveTextContent('lead');
    expect(await box('workflow:s/w#ship')).toHaveTextContent('Direct control');
    expect(ownershipAsked).toEqual(['s/w']);
    fireEvent.click(await box('workflow:s/w'));
    await waitFor(() =>
      expect(
        screen
          .getByRole('region', { name: 'Supervision overview' })
          .querySelector('[data-id="workflow:s/w#build"]')
      ).toBeNull()
    );
  });

  it('does nothing when a box that is not a supervisor or a workflow with a session is clicked', async () => {
    supervisors = [sup('lead')];
    assignments = [
      {
        id: 'a1',
        supervisorId: 'lead',
        scope: 'server',
        enabled: true,
        source: 'MANUAL',
      },
    ];
    renderAt('/supervisors?view=map');
    fireEvent.click(await box('target:lead:server'));
    expect(screen.getByLabelText('where')).toHaveTextContent(
      '/supervisors?view=map'
    );
  });

  it('says when there is nothing to map, and when it cannot be loaded, with retry', async () => {
    const user = userEvent.setup();
    server.use(
      http.get('/api/supervision/summary', () =>
        ok({ unowned: 0, unownedWorkflows: [] })
      )
    );
    const first = renderAt('/supervisors?view=map');
    expect(await screen.findByText('Nothing to map yet')).toBeInTheDocument();
    first.unmount();
    failing = true;
    renderAt('/supervisors?view=map');
    expect(
      await screen.findByText('Failed to load the map.')
    ).toBeInTheDocument();
    failing = false;
    supervisors = [sup('lead')];
    await user.click(screen.getByRole('button', { name: /Retry/ }));
    expect(await box('supervisor:lead')).toBeInTheDocument();
  });
});
