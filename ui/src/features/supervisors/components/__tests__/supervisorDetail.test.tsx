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

const decision = (time: string) => ({
  time,
  supervisorId: 'lead',
  outcome: 'applied',
  command: 'PauseWorkflowCommand',
  executionId: 'e1',
  reason: null,
});

let supervisors: unknown[] = [];
let mine: unknown[] = [];
let decisions: unknown[] = [];

const server = setupServer(
  http.get('/api/supervisors', () => ok(supervisors)),
  http.get('/api/supervisors/assignments', () => ok([])),
  http.get('/api/supervisors/:id/assignments', () => ok(mine)),
  http.get('/api/supervisor-engines', () =>
    ok([
      { type: 'rules', displayName: 'Rules', category: 'RULES', icon: 'rule' },
    ])
  ),
  http.get('/api/supervisor-engines/:type', () =>
    ok({
      type: 'rules',
      displayName: 'Rules',
      category: 'RULES',
      icon: 'rule',
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
      nodes: {},
    });
  }),
  http.get('/api/supervision/summary', () =>
    ok({
      unowned: 1,
      unownedWorkflows: [{ sessionId: 's1', workflowId: 'w1' }],
    })
  ),
  http.get('/api/supervisors/decisions', () => ok(decisions))
);

beforeAll(() => server.listen());
afterEach(() => {
  server.resetHandlers();
  supervisors = [];
  mine = [];
  decisions = [];
});
afterAll(() => server.close());

const renderAt = (url: string) =>
  render(
    <QueryClientProvider client={createTestQueryClient()}>
      <MemoryRouter initialEntries={[url]}>
        <SupervisorsPage />
      </MemoryRouter>
    </QueryClientProvider>
  );

describe('the supervisor detail', () => {
  it('shows the header card with its chips and the numbers at a glance', async () => {
    supervisors = [
      sup('lead', {
        applied: 7,
        rejected: 2,
        rateLimited: 1,
        parents: ['boss'],
      }),
      sup('boss'),
    ];
    decisions = [decision('2026-11-09T12:00:00Z')];
    renderAt('/supervisors?supervisor=lead');
    const glance = await screen.findByRole('region', { name: 'At a glance' });
    expect(within(glance).getByText('Applied')).toBeInTheDocument();
    expect(within(glance).getByText('7')).toBeInTheDocument();
    expect(within(glance).getByText('Refused')).toBeInTheDocument();
    expect(within(glance).getByText('Rate limited')).toBeInTheDocument();
    expect(
      screen.getByRole('heading', { name: 'lead', level: 2 })
    ).toBeInTheDocument();
    expect(screen.getByText('Live')).toBeInTheDocument();
    expect(screen.getByText(/Reports to/)).toBeInTheDocument();
  });

  it('warns about a dry run in the header and the callout', async () => {
    supervisors = [sup('lead', { dryRun: true })];
    renderAt('/supervisors?supervisor=lead');
    expect(await screen.findByText('Dry run')).toBeInTheDocument();
    expect(
      screen.getByText('Dry run: recorded, not applied')
    ).toBeInTheDocument();
    expect(
      screen.getByText(/Turn dry run off in Edit supervisor/)
    ).toBeInTheDocument();
  });

  it('shows no engine facts for a decision maker type it does not know', async () => {
    const user = userEvent.setup();
    supervisors = [sup('lead', { type: 'custom-engine' })];
    renderAt('/supervisors?supervisor=lead');
    await user.click(
      await screen.findByRole('tab', { name: 'Decision maker' })
    );
    expect(
      await screen.findByRole('tab', { name: 'Decision maker' })
    ).toHaveAttribute('aria-selected', 'true');
    expect(screen.queryByText('Open the rules')).not.toBeInTheDocument();
  });

  it('explains direct control in its operations and decision maker tabs', async () => {
    const user = userEvent.setup();
    server.use(
      http.get('/api/supervision/ownership', () =>
        ok({
          sessionId: null,
          workflowId: null,
          defaultSupervisor: false,
          run: null,
          nodes: {},
        })
      )
    );
    renderAt('/supervisors?supervisor=default');
    await user.click(
      await screen.findByRole('tab', { name: 'Allowed operations' })
    );
    expect(
      await screen.findByText(/Direct control is switched off/)
    ).toBeInTheDocument();
    await user.click(screen.getByRole('tab', { name: 'Decision maker' }));
    expect(await screen.findByText(/decides with/)).toBeInTheDocument();
  });

  it('says direct control is on in the operations tab when the default supervisor is on', async () => {
    const user = userEvent.setup();
    renderAt('/supervisors?supervisor=default');
    await user.click(
      await screen.findByRole('tab', { name: 'Allowed operations' })
    );
    expect(
      await screen.findByText(/Everything: people and agents/)
    ).toBeInTheDocument();
  });

  it('counts no rules when the rules document lists none', async () => {
    const user = userEvent.setup();
    server.use(
      http.get('/api/supervisors/:id/engine/rules', () =>
        ok({ version: 3, rules: { schemaVersion: 1 } })
      )
    );
    supervisors = [sup('lead')];
    renderAt('/supervisors?supervisor=lead');
    await user.click(
      await screen.findByRole('tab', { name: 'Decision maker' })
    );
    expect(await screen.findByText('0 · version 3')).toBeInTheDocument();
  });

  it('says the rules could not be read when the rules request fails', async () => {
    const user = userEvent.setup();
    server.use(
      http.get('/api/supervisors/:id/engine/rules', () =>
        HttpResponse.json(
          { timestamp: 't', status: 500, message: 'boom', path: '/' },
          { status: 500 }
        )
      )
    );
    supervisors = [sup('lead')];
    renderAt('/supervisors?supervisor=lead');
    await user.click(
      await screen.findByRole('tab', { name: 'Decision maker' })
    );
    expect(await screen.findByText('Could not be read')).toBeInTheDocument();
  });

  it('shows the decision workflow and its limits of a workflow decision maker', async () => {
    const user = userEvent.setup();
    supervisors = [
      sup('lead', {
        type: 'workflow',
        settings: {
          sessionId: 'decide',
          workflowId: 'judge',
          events: ['run.failed', 'run.stalled'],
          maxConcurrent: 4,
          timeout: 90,
        },
      }),
    ];
    renderAt('/supervisors?supervisor=lead');
    await user.click(
      await screen.findByRole('tab', { name: 'Decision maker' })
    );
    expect(
      await screen.findByRole('link', { name: 'decide / judge' })
    ).toBeInTheDocument();
    expect(screen.getByText('run.failed, run.stalled')).toBeInTheDocument();
    expect(screen.getByText('4')).toBeInTheDocument();
    expect(screen.getByText('90 s')).toBeInTheDocument();
  });

  it('shows the defaults of a workflow decision maker with no settings', async () => {
    const user = userEvent.setup();
    supervisors = [sup('lead', { type: 'workflow', settings: {} })];
    renderAt('/supervisors?supervisor=lead');
    await user.click(
      await screen.findByRole('tab', { name: 'Decision maker' })
    );
    expect(await screen.findByText('not set')).toBeInTheDocument();
    expect(screen.getAllByText('–').length).toBeGreaterThan(0);
    expect(screen.getAllByText('2').length).toBeGreaterThan(0);
    expect(screen.getByText('60 s')).toBeInTheDocument();
  });

  it('shows the program and arguments of a process decision maker', async () => {
    const user = userEvent.setup();
    supervisors = [
      sup('lead', {
        type: 'process',
        settings: { command: 'run-guard.sh', args: '--strict\n--quiet' },
      }),
    ];
    renderAt('/supervisors?supervisor=lead');
    await user.click(
      await screen.findByRole('tab', { name: 'Decision maker' })
    );
    expect(await screen.findByText('run-guard.sh')).toBeInTheDocument();
    expect(screen.getByText('--strict --quiet')).toBeInTheDocument();
  });

  it('shows the defaults of a process decision maker with no settings', async () => {
    const user = userEvent.setup();
    supervisors = [sup('lead', { type: 'process', settings: {} })];
    renderAt('/supervisors?supervisor=lead');
    await user.click(
      await screen.findByRole('tab', { name: 'Decision maker' })
    );
    expect(await screen.findByText('not set')).toBeInTheDocument();
    expect(screen.getByText('none')).toBeInTheDocument();
  });

  it('falls back to the owns tab in direct control for a supervisor-only section', async () => {
    renderAt('/supervisors?supervisor=default&section=settings');
    expect(await screen.findByRole('tab', { name: /Owns/ })).toHaveAttribute(
      'aria-selected',
      'true'
    );
  });

  it('opens the owns tab from the "more" box of the supervision map', async () => {
    const user = userEvent.setup();
    supervisors = [sup('lead')];
    // more workflows than the map draws, so it ends in a "+1 more" box
    mine = Array.from({ length: 7 }, (_, i) => ({
      id: `a${i}`,
      supervisorId: 'lead',
      scope: `workflow:s1/w${i}`,
      enabled: true,
      source: 'MANUAL',
    }));
    renderAt('/supervisors?supervisor=lead');
    await user.click(
      await screen.findByRole('tab', { name: 'Allowed operations' })
    );
    const map = await screen.findByRole('region', {
      name: 'Supervision map',
    });
    await expect
      .poll(() => map.querySelector('[data-id="more"]') !== null)
      .toBe(true);
    fireEvent.click(map.querySelector('[data-id="more"]') as HTMLElement);
    expect(await screen.findByRole('tab', { name: /Owns/ })).toHaveAttribute(
      'aria-selected',
      'true'
    );
  });

  describe('the links of the supervision map and the hierarchy', () => {
    function PathProbe() {
      const { pathname, search } = useLocation();
      return <output aria-label="where">{pathname + search}</output>;
    }
    const renderWithPath = (url: string) =>
      render(
        <QueryClientProvider client={createTestQueryClient()}>
          <MemoryRouter initialEntries={[url]}>
            <SupervisorsPage />
            <PathProbe />
          </MemoryRouter>
        </QueryClientProvider>
      );
    const openMap = async () => {
      const user = userEvent.setup();
      await user.click(
        await screen.findByRole('tab', { name: 'Allowed operations' })
      );
      return screen.findByRole('region', { name: 'Supervision map' });
    };

    it('opens a child supervisor when its box of the map is clicked', async () => {
      supervisors = [sup('lead'), sup('kid', { parents: ['lead'] })];
      renderWithPath('/supervisors?supervisor=lead');
      const map = await openMap();
      await expect
        .poll(() => map.querySelector('[data-id="child:kid"]') !== null)
        .toBe(true);
      fireEvent.click(
        map.querySelector('[data-id="child:kid"]') as HTMLElement
      );
      await waitFor(() =>
        expect(screen.getByLabelText('where')).toHaveTextContent(
          'supervisor=kid'
        )
      );
    });

    it('keeps the supervisor when its own box of the map is clicked', async () => {
      supervisors = [sup('lead')];
      renderWithPath('/supervisors?supervisor=lead');
      const map = await openMap();
      await expect
        .poll(() => map.querySelector('[data-id="self"]') !== null)
        .toBe(true);
      fireEvent.click(map.querySelector('[data-id="self"]') as HTMLElement);
      expect(screen.getByLabelText('where')).toHaveTextContent(
        'supervisor=lead'
      );
    });

    it('goes to the workflow when its box of the map is clicked', async () => {
      supervisors = [sup('lead')];
      mine = [
        {
          id: 'a0',
          supervisorId: 'lead',
          scope: 'workflow:s1/w0',
          enabled: true,
          source: 'MANUAL',
        },
      ];
      renderWithPath('/supervisors?supervisor=lead');
      const map = await openMap();
      await expect
        .poll(() => map.querySelector('[data-id="workflow:s1/w0"]') !== null)
        .toBe(true);
      fireEvent.click(
        map.querySelector('[data-id="workflow:s1/w0"]') as HTMLElement
      );
      await waitFor(() =>
        expect(screen.getByLabelText('where')).toHaveTextContent(
          '/sessions/s1/workflow/w0'
        )
      );
    });

    it('draws the nodes of a whole workflow that the supervisor owns', async () => {
      supervisors = [sup('lead')];
      mine = [
        {
          id: 'a0',
          supervisorId: 'lead',
          scope: 'workflow:s1/w0',
          enabled: true,
          source: 'MANUAL',
        },
      ];
      server.use(
        http.get('/api/supervision/ownership', () =>
          ok({
            sessionId: 's1',
            workflowId: 'w0',
            defaultSupervisor: false,
            run: null,
            nodes: { deploy: 'lead', smoke: 'someone-else' },
          })
        )
      );
      renderWithPath('/supervisors?supervisor=lead');
      const map = await openMap();
      await expect
        .poll(() => map.querySelector('[data-id*="deploy"]') !== null)
        .toBe(true);
      expect(map.querySelector('[data-id*="smoke"]')).toBeNull();
    });

    it('reads a supervisor without parents as reporting to nobody', async () => {
      const user = userEvent.setup();
      supervisors = [
        sup('lead', { parents: undefined }),
        sup('kid', { parents: undefined }),
      ];
      renderWithPath('/supervisors?supervisor=lead');
      await user.click(await screen.findByRole('tab', { name: 'Hierarchy' }));
      expect(
        await screen.findByText(
          'nobody; people answer what it asks in the inbox'
        )
      ).toBeInTheDocument();
    });
  });
});
