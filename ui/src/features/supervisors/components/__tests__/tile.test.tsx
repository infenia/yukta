// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { screen } from '@testing-library/react';
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
  it('counts active supervisors, conflicts in the last hour and decisions per minute', async () => {
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
          decision('arbitrated', 120_000),
          decision('flapping-blocked', 30 * 60_000),
          decision('coalesced', 2 * 3_600_000),
          decision('applied', 2 * 3_600_000),
        ])
      )
    );
    renderWithProviders(<SupervisorsTile />);
    expect(await screen.findByText('2 active')).toBeInTheDocument();
    expect(
      await screen.findByText(
        '2 conflicts in the last hour · 0.2 decisions/min'
      )
    ).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Supervisors' })).toHaveAttribute(
      'href',
      '/supervisors'
    );
  });

  it('shows placeholders while loading and when nothing came back', async () => {
    server.use(
      http.get('/api/supervisors/status', () => ok([])),
      http.get('/api/supervisors/decisions', () => ok([]))
    );
    renderWithProviders(<SupervisorsTile />);
    expect(screen.getByText('– active')).toBeInTheDocument();
    expect(screen.getByText('loading decisions')).toBeInTheDocument();
    expect(await screen.findByText('0 active')).toBeInTheDocument();
    expect(
      await screen.findByText(
        '0 conflicts in the last hour · 0.0 decisions/min'
      )
    ).toBeInTheDocument();
  });
});
