// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { renderWithProviders } from '@/test/utils/testUtils';
import { SupervisorsSummary } from '../SupervisorsSummary';

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

const handlers = (decisions: unknown[], unowned = 3, conflicts = 2) => [
  http.get('/api/supervisors/assignments', () =>
    ok([{ id: 'a1' }, { id: 'a2' }, { id: 'a3' }])
  ),
  http.get('/api/supervisors', () => ok([{ id: 'ops' }, { id: 'ci' }])),
  http.get('/api/supervisors/decisions', () => ok(decisions)),
  http.get('/api/supervision/summary', () =>
    ok({ unowned, unownedWorkflows: [], conflicts })
  ),
];

describe('SupervisorsSummary', () => {
  it('shows the counts, the heat strip and a link per busy supervisor', async () => {
    server.use(
      ...handlers([
        decision('applied', 60_000),
        decision('rejected', 120_000),
        decision('applied', 30 * 60_000),
      ])
    );
    renderWithProviders(<SupervisorsSummary />);
    const tile = (label: string) => {
      const name = screen
        .getAllByText(label)
        .find((e) => e.classList.contains('text-label-md'));
      return name?.closest('div') as HTMLElement;
    };
    await waitFor(() => expect(tile('Supervisors')).toHaveTextContent('2'));
    expect(tile('Assignments')).toHaveTextContent('3');
    expect(tile('Under direct control')).toHaveTextContent('3');
    expect(tile('Conflicts')).toHaveTextContent('2');
    expect(tile('Applied')).toHaveTextContent('2');
    expect(tile('Refused')).toHaveTextContent('1');
    expect(screen.getByText(/Last 3 decisions/)).toBeInTheDocument();
    expect(
      screen.getByRole('img', { name: /Supervisor decisions/ })
    ).toBeVisible();
    expect(screen.getByRole('link', { name: 's' })).toHaveAttribute(
      'href',
      '/supervisors?supervisor=s'
    );
    expect(
      screen.getByRole('link', { name: 'Open supervisors' })
    ).toHaveAttribute('href', '/supervisors');
  });

  it('collapses the strip to one line when there are no decisions', async () => {
    server.use(...handlers([], 0, 0));
    renderWithProviders(<SupervisorsSummary />);
    expect(
      await screen.findByText('No supervisor decisions yet.')
    ).toBeVisible();
    expect(screen.queryByRole('img')).not.toBeInTheDocument();
  });
});
