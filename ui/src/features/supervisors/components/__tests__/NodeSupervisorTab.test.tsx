// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { QueryClientProvider } from '@tanstack/react-query';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
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
