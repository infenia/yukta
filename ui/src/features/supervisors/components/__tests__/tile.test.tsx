// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { renderWithProviders } from '@/test/utils/testUtils';
import { SupervisorsTile } from '../SupervisorsTile';

const ok = (data: unknown) =>
  HttpResponse.json({
    timestamp: 't',
    status: 200,
    message: 'ok',
    data,
    path: '/',
  });
const ago = (ms: number) => new Date(Date.now() - ms).toISOString();
const decision = (outcome: string, ms: number) => ({
  time: ago(ms),
  supervisorId: 's',
  outcome,
  command: 'PAUSE',
  executionId: 'e',
  reason: null,
});

const server = setupServer();
beforeAll(() => server.listen());
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

describe('SupervisorsTile', () => {
  const tile = () => screen.getByRole('link', { name: 'Supervisors' });

  it('counts active supervisors, unowned workflows, conflicts and decisions per minute', async () => {
    server.use(
      http.get('/api/supervisors/status', () =>
        ok([
          { id: 'a', kind: 'SUPERVISOR' },
          { id: 'b', kind: 'SUPERVISOR' },
          { id: 'c', kind: 'CONTROL_PLANE' },
        ])
      ),
      http.get('/api/supervisors/decisions', () =>
        ok([
          decision('applied', 60_000),
          decision('rejected', 120_000),
          decision('applied', 30 * 60_000),
        ])
      ),
      http.get('/api/supervision/summary', () =>
        ok({
          unowned: 3,
          unownedWorkflows: [{ sessionId: 's', workflowId: 'w' }],
          conflicts: 2,
        })
      )
    );
    renderWithProviders(<SupervisorsTile />);
    expect(await screen.findByText('2 active')).toBeInTheDocument();
    await waitFor(() =>
      expect(tile()).toHaveTextContent(
        '3 workflows nobody owns · 2 conflicts · 0.2 decisions/min'
      )
    );
    expect(screen.getByText('2 conflicts')).toHaveClass('text-warning');
    expect(tile()).toHaveAttribute('href', '/supervisors');
  });

  it('says one of each in the singular, and leaves the summary out until it is there', async () => {
    server.use(
      http.get('/api/supervisors/status', () => ok([])),
      http.get('/api/supervisors/decisions', () => ok([])),
      http.get('/api/supervision/summary', () =>
        ok({ unowned: 1, unownedWorkflows: [], conflicts: 1 })
      )
    );
    renderWithProviders(<SupervisorsTile />);
    expect(screen.getByText('– active')).toBeInTheDocument();
    expect(tile()).toHaveTextContent('loading decisions');
    expect(tile()).not.toHaveTextContent('nobody owns');
    await waitFor(() =>
      expect(tile()).toHaveTextContent(
        '1 workflow nobody owns · 1 conflict · 0.0 decisions/min'
      )
    );
  });

  it('shows no conflicts without the warning colour', async () => {
    server.use(
      http.get('/api/supervisors/status', () => ok([])),
      http.get('/api/supervisors/decisions', () => ok([])),
      http.get('/api/supervision/summary', () =>
        ok({ unowned: 0, unownedWorkflows: [], conflicts: 0 })
      )
    );
    renderWithProviders(<SupervisorsTile />);
    expect(await screen.findByText('0 conflicts')).not.toHaveClass(
      'text-warning'
    );
  });
});
