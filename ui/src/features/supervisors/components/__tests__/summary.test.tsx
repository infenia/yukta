// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { expectTooltip, renderWithProviders } from '@/test/utils/testUtils';
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

const handlers = (decisions: unknown[], unowned = 3) => [
  http.get('/api/supervisors/assignments', () =>
    ok([{ id: 'a1' }, { id: 'a2' }, { id: 'a3' }])
  ),
  http.get('/api/supervisors', () => ok([{ id: 'ops' }, { id: 'ci' }])),
  http.get('/api/supervisors/decisions', () => ok(decisions)),
  http.get('/api/supervision/summary', () =>
    ok({ unowned, unownedWorkflows: [] })
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
    const tile = (label: string) =>
      screen.getByText(label, { selector: 'dt' }).closest('div') as HTMLElement;
    await waitFor(() => expect(tile('Supervisors')).toHaveTextContent('2'));
    expect(tile('Assignments')).toHaveTextContent('3');
    expect(tile('Direct control')).toHaveTextContent('3');
    await expectTooltip(
      tile('Direct control'),
      '3 workflows nobody supervises'
    );
    expect(tile('Applied / refused')).toHaveTextContent('2 / 1');
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

  it('ranks the busiest supervisors first', async () => {
    server.use(
      ...handlers([
        decision('applied', 60_000),
        { ...decision('applied', 90_000), supervisorId: 'ci' },
        { ...decision('applied', 120_000), supervisorId: 'ci' },
      ])
    );
    renderWithProviders(<SupervisorsSummary />);
    const links = await screen.findAllByRole('link', { name: /^(s|ci)$/ });
    expect(links.map((l) => l.textContent)).toEqual(['ci', 's']);
  });

  it('collapses the strip to one line when there are no decisions', async () => {
    server.use(...handlers([], 0));
    renderWithProviders(<SupervisorsSummary />);
    expect(
      await screen.findByText('No supervisor decisions yet.')
    ).toBeVisible();
    expect(screen.queryByRole('img')).not.toBeInTheDocument();
  });
});
