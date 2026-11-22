// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
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
  it('draws the sparkline of applied decisions over their span', async () => {
    supervisors = [sup('lead')];
    decisions = [
      decision('2026-11-09T12:00:00Z'),
      decision('2026-11-09T12:05:00Z'),
      decision('2026-11-09T12:10:00Z'),
    ];
    renderAt('/supervisors?supervisor=lead');
    const glance = await screen.findByRole('region', { name: 'At a glance' });
    expect(screen.getByText('Applied')).toBeInTheDocument();
    // the sparkline is drawn only once the decisions have arrived
    await expect
      .poll(() => glance.querySelector('polyline') !== null)
      .toBe(true);
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
});
