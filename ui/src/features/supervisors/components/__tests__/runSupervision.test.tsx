// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { QueryClientProvider } from '@tanstack/react-query';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { createTestQueryClient } from '@/test/utils/testUtils';
import { WorkflowSupervision } from '../WorkflowSupervision';
import { ActionsTimeline } from '../ActionsTimeline';
import type { Decision } from '../../types';

const ok = (data: unknown) =>
  HttpResponse.json({
    timestamp: 't',
    status: 200,
    message: 'ok',
    data,
    path: '/',
  });

const decision = (extra: Partial<Decision>): Decision => ({
  time: '2026-11-22T10:00:00Z',
  supervisorId: 'guard',
  outcome: 'applied',
  command: 'PauseWorkflowCommand',
  executionId: 'e1',
  reason: null,
  workflowId: 'w',
  ...extra,
});

let decisions: Decision[] = [];
const server = setupServer(
  http.get('/api/supervision/ownership', () =>
    ok({
      sessionId: 's',
      workflowId: 'w',
      defaultSupervisor: true,
      run: 'default',
      nodes: {},
    })
  ),
  http.get('/api/supervision', () => ok({})),
  http.get('/api/supervisors', () => ok([])),
  http.get('/api/supervisor-engines', () => ok([])),
  http.get('/api/supervisors/assignments', () => ok([])),
  http.get('/api/supervisors/decisions', () => ok(decisions))
);
beforeAll(() => server.listen());
afterEach(() => {
  server.resetHandlers();
  decisions = [];
});
afterAll(() => server.close());

const sheet = (executionId: string | null, entry = '/?supervision=1') =>
  render(
    <QueryClientProvider client={createTestQueryClient()}>
      <MemoryRouter initialEntries={[entry]}>
        <WorkflowSupervision
          sessionId="s"
          workflowId="w"
          executionId={executionId}
        />
      </MemoryRouter>
    </QueryClientProvider>
  );

describe('WorkflowSupervision actions', () => {
  it('lists what every supervisor did on this run, and only on this run', async () => {
    decisions = [
      decision({ nodeId: 'build', command: 'StopNodeCommand', reason: 'late' }),
      decision({ supervisorId: 'lead', outcome: 'rejected', because: 'cap' }),
      decision({ executionId: 'other', command: 'ResumeWorkflowCommand' }),
    ];
    sheet('e1');
    const dialog = await screen.findByRole('dialog', {
      name: 'Who controls this workflow',
    });
    const actions = await within(dialog).findByRole('region', {
      name: 'Supervisor actions',
    });
    await waitFor(() =>
      expect(within(actions).getAllByRole('listitem')).toHaveLength(2)
    );
    expect(actions).toHaveTextContent('Actions taken on this run');
    expect(actions).toHaveTextContent('2 actions');
    expect(actions).toHaveTextContent('Stop');
    expect(actions).toHaveTextContent('node build · applied');
    expect(actions).toHaveTextContent('late');
    expect(actions).toHaveTextContent('whole run · rejected');
    expect(actions).toHaveTextContent('cap');
    expect(within(actions).getByRole('link', { name: 'lead' })).toHaveAttribute(
      'href',
      '/supervisors?supervisor=lead'
    );
    expect(actions).not.toHaveTextContent('Resume');
  });

  it('says so when nothing was done, and scopes to the workflow without a run', async () => {
    decisions = [
      decision({ executionId: 'x', workflowId: 'other' }),
      decision({ executionId: 'y' }),
    ];
    sheet(null);
    const actions = await screen.findByRole('region', {
      name: 'Supervisor actions',
    });
    await waitFor(() =>
      expect(within(actions).getAllByRole('listitem')).toHaveLength(1)
    );
    expect(actions).toHaveTextContent('Actions taken on this workflow');
    expect(actions).toHaveTextContent('1 action');
    expect(actions).not.toHaveTextContent('1 actions');
  });

  it('shows an empty line when no supervisor has acted', async () => {
    sheet('e1');
    expect(
      await screen.findByText('No supervisor has acted here yet.')
    ).toBeInTheDocument();
  });

  it('closes from Escape as well as its button', async () => {
    sheet('e1');
    await screen.findByRole('dialog');
    await userEvent.setup().keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  });
});

describe('ActionsTimeline', () => {
  it('says it is loading before the first answer', () => {
    render(
      <MemoryRouter>
        <ActionsTimeline actions={[]} loading empty="Nothing." />
      </MemoryRouter>
    );
    expect(screen.getByText('Loading…')).toBeInTheDocument();
    expect(screen.queryByText('Nothing.')).toBeNull();
  });
});
