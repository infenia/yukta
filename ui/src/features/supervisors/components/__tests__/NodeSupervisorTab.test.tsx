// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { QueryClientProvider } from '@tanstack/react-query';
import { setupServer } from 'msw/node';
import { delay, http, HttpResponse } from 'msw';
import { setMe, type Me } from '@/lib/identity';
import { createTestQueryClient } from '@/test/utils/testUtils';
import { NodeSupervisorTab } from '../NodeSupervisorTab';

const ok = (data: unknown) =>
  HttpResponse.json({
    timestamp: 't',
    status: 200,
    message: 'ok',
    data,
    path: '/',
  });

let ownership: unknown = {};
let ownershipFails = false;
let capabilities: Record<string, unknown> = {};
let decisions: unknown[] = [];
const server = setupServer(
  http.get('/api/supervision/ownership', () =>
    ownershipFails
      ? HttpResponse.json(
          { timestamp: 't', status: 500, message: 'no', path: '/' },
          { status: 500 }
        )
      : ok(ownership)
  ),
  http.get('/api/supervision', () => ok(capabilities)),
  http.get('/api/supervisors/decisions', () => ok(decisions)),
  http.get('/api/supervisors', () =>
    ok([
      {
        id: 'lead',
        type: 'RULES',
        description: 'Keeps the release gates.',
        dryRun: false,
        system: false,
        parents: [],
      },
    ])
  ),
  http.get('/api/supervisor-engines', () =>
    ok([{ type: 'RULES', displayName: 'Rules', category: 'RULES', icon: null }])
  )
);
beforeAll(() => server.listen());
afterEach(() => {
  server.resetHandlers();
  setMe(null);
  ownership = {};
  ownershipFails = false;
  capabilities = {};
  decisions = [];
});
afterAll(() => server.close());

const show = (executionId: string | null = 'e1') =>
  render(
    <QueryClientProvider client={createTestQueryClient()}>
      <MemoryRouter>
        <NodeSupervisorTab
          sessionId="s"
          workflowId="w"
          executionId={executionId}
          nodeId="build"
        />
      </MemoryRouter>
    </QueryClientProvider>
  );

const owned = (owner: string) => ({
  sessionId: 's',
  workflowId: 'w',
  defaultSupervisor: true,
  run: 'default',
  nodes: { build: owner },
});

describe('NodeSupervisorTab', () => {
  it('shows the supervisor of the run when only the run level is owned', async () => {
    ownership = { ...owned('default'), run: 'lead' };
    show();
    expect(await screen.findByText('lead')).toBeInTheDocument();
    expect(
      screen.getByText('Controls the whole run, including this node')
    ).toBeInTheDocument();
  });

  it('shows what the supervisor is, who it reports to and how many commands are allowed', async () => {
    server.use(
      http.get('/api/supervisors', () =>
        ok([
          {
            id: 'lead',
            type: 'RULES',
            description: 'Keeps the release gates.',
            dryRun: true,
            system: true,
            parents: ['boss', 'chief'],
          },
        ])
      )
    );
    ownership = owned('lead');
    capabilities = {
      pauseNode: { allowed: true, via: 'lead', reason: null },
      stepNode: { allowed: false, via: 'lead', reason: 'No stepping' },
    };
    show();
    expect(await screen.findByText('Dry run')).toBeInTheDocument();
    expect(screen.getByText('Built in')).toBeInTheDocument();
    expect(screen.getByText('Keeps the release gates.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'boss' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'chief' })).toBeInTheDocument();
    expect(await screen.findByText('1 of 2 allowed')).toBeInTheDocument();
  });

  it('lists what the supervisor did to this node and to its run, not what it did elsewhere', async () => {
    const base = {
      supervisorId: 'lead',
      workflowId: 'w',
      executionId: 'e1',
      reason: null,
    };
    decisions = [
      {
        ...base,
        time: '2026-01-01T10:00:00',
        command: 'PauseWorkflowCommand',
        outcome: 'applied',
        because: 'CPU is short',
      },
      {
        ...base,
        time: '2026-01-01T10:01:00',
        command: 'StopNodeCommand',
        nodeId: 'build',
        outcome: 'rejected',
        reason: 'Not allowed',
      },
      {
        ...base,
        time: 't',
        command: 'SkipNodeCommand',
        nodeId: 'other',
        outcome: 'applied',
      },
      {
        ...base,
        time: 't',
        command: 'ResumeWorkflowCommand',
        executionId: 'e2',
        outcome: 'applied',
      },
      {
        ...base,
        time: 't',
        command: 'StepNodeCommand',
        supervisorId: 'x',
        nodeId: 'build',
        outcome: 'applied',
      },
    ];
    ownership = { ...owned('default'), run: 'lead' };
    show();
    expect(await screen.findByText('Pause')).toBeInTheDocument();
    expect(screen.getByText('CPU is short')).toBeInTheDocument();
    expect(screen.getByText('Not allowed')).toBeInTheDocument();
    expect(screen.queryByText('Skip')).toBeNull();
    expect(screen.queryByText('Resume')).toBeNull();
    expect(screen.queryByText('Step')).toBeNull();
  });

  it('lists the run-level actions of the workflow when no run is open, with or without a reason', async () => {
    decisions = [
      {
        supervisorId: 'lead',
        workflowId: 'w',
        executionId: 'e9',
        reason: null,
        time: '2026-01-01T10:00:00',
        command: 'PauseWorkflowCommand',
        outcome: 'applied',
      },
    ];
    ownership = { ...owned('default'), run: 'lead' };
    show(null);
    expect(await screen.findByText('Pause')).toBeInTheDocument();
  });

  it('says it is loading while the actions are on their way', async () => {
    server.use(
      http.get('/api/supervisors/decisions', async () => {
        await delay(100);
        return ok([]);
      })
    );
    ownership = owned('lead');
    show();
    expect(await screen.findByText('Loading…')).toBeInTheDocument();
    expect(
      await screen.findByText('No actions taken yet by this supervisor.')
    ).toBeInTheDocument();
  });

  it('says so when the supervisor has not acted yet', async () => {
    ownership = owned('lead');
    show();
    expect(
      await screen.findByText('No actions taken yet by this supervisor.')
    ).toBeInTheDocument();
  });

  it('shows the supervisor of the node and which of its commands are allowed', async () => {
    ownership = owned('lead');
    capabilities = {
      pauseNode: { allowed: true, via: 'lead', reason: null },
      stopNode: { allowed: false, via: 'lead', reason: 'Only lead stops it' },
      skipNode: { allowed: false, via: 'lead', reason: null },
    };
    show();
    expect(await screen.findByRole('link', { name: 'lead' })).toHaveAttribute(
      'href',
      '/supervisors?supervisor=lead'
    );
    expect(await screen.findByText('Rules')).toBeInTheDocument();
    expect(screen.getByText('Controls this node')).toBeInTheDocument();
    expect(await screen.findByText('Pause')).toBeInTheDocument();
    expect(screen.getByText('Only lead stops it')).toBeInTheDocument();
    // a command the owner refuses without a reason still says who owns it
    expect(screen.getByText('Skip')).toBeInTheDocument();
    expect(screen.getByText('Stop')).toBeInTheDocument();
  });

  it('says a node nobody supervises is under Direct control, and a role too low blocks a command', async () => {
    ownership = owned('default');
    capabilities = {
      resumeNode: { allowed: true, via: 'default', reason: null },
    };
    setMe({
      role: 'VIEWER',
      permissions: { controlRuns: false },
    } as unknown as Me);
    show();
    expect(await screen.findByText('Direct control')).toBeInTheDocument();
    expect(await screen.findByText(/Needs a higher role/)).toBeInTheDocument();
  });

  it('asks for a run to be opened when none is, and says when the owner cannot be read', async () => {
    ownership = {
      sessionId: 's',
      workflowId: 'w',
      defaultSupervisor: true,
      run: 'default',
      nodes: {},
    };
    const { unmount } = show(null);
    expect(
      await screen.findByText(/Open a run to see which commands/)
    ).toBeInTheDocument();
    unmount();

    ownershipFails = true;
    show();
    expect(
      await screen.findByText('Could not load who controls this node.')
    ).toBeInTheDocument();
  });

  it('says the server named no commands when a run is open', async () => {
    ownership = owned('default');
    show();
    expect(
      await screen.findByText(
        'The server did not say which commands are allowed.'
      )
    ).toBeInTheDocument();
  });
});
