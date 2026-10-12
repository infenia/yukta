// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { screen, within } from '@testing-library/react';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { renderWithProviders } from '@/test/utils/testUtils';
import { SupervisorsPanel, type Supervisor } from '../SupervisorsPanel';

const ok = (data: unknown) =>
  HttpResponse.json({
    timestamp: 't',
    status: 200,
    message: 'ok',
    data,
    path: '/',
  });

const supervisor = (overrides: Partial<Supervisor>): Supervisor => ({
  id: 'rules',
  kind: 'SUPERVISOR',
  type: 'RuleSupervisor',
  order: 0,
  dryRun: false,
  maxCommandsPerSecond: 20,
  applied: 0,
  rejected: 0,
  recorded: 0,
  rateLimited: 0,
  ...overrides,
});

let supervisors: Supervisor[] = [];
let fail = false;

const server = setupServer(
  http.get('/api/supervisors', () =>
    fail
      ? HttpResponse.json({ message: 'x' }, { status: 500 })
      : ok(supervisors)
  )
);

beforeAll(() => server.listen());
afterEach(() => {
  supervisors = [];
  fail = false;
});
afterAll(() => server.close());

describe('SupervisorsPanel', () => {
  it('says so when nothing is running', async () => {
    renderWithProviders(<SupervisorsPanel />);
    expect(await screen.findByText(/None running/)).toBeInTheDocument();
  });

  it('lists supervisors and control planes with their command counts', async () => {
    supervisors = [
      supervisor({
        id: 'rules',
        applied: 3,
        rejected: 1,
        recorded: 0,
        rateLimited: 2,
      }),
      supervisor({
        id: 'model-control-plane',
        kind: 'CONTROL_PLANE',
        type: 'ModelControlPlane',
        order: 5,
        dryRun: true,
        maxCommandsPerSecond: 7,
        recorded: 4,
      }),
    ];
    renderWithProviders(<SupervisorsPanel />);

    const rules = (await screen.findByText('rules')).closest(
      'tr'
    ) as HTMLElement;
    expect(within(rules).getByText('Supervisor')).toBeInTheDocument();
    expect(within(rules).getByText('RuleSupervisor')).toBeInTheDocument();
    expect(within(rules).getByText(/Live/)).toBeInTheDocument();
    expect(
      within(rules)
        .getAllByRole('cell')
        .map((cell) => cell.textContent)
    ).toEqual([
      'rules',
      'Supervisor',
      'RuleSupervisor',
      '0',
      'Live · max 20/s',
      '3',
      '1',
      '0',
      '2',
    ]);

    const plane = screen
      .getByText('model-control-plane')
      .closest('tr') as HTMLElement;
    expect(within(plane).getByText('Control plane')).toBeInTheDocument();
    expect(within(plane).getByText(/Dry run/)).toBeInTheDocument();
    expect(within(plane).getByText(/max 7\/s/)).toBeInTheDocument();
    expect(within(plane).getByText('4')).toBeInTheDocument();
  });

  it('reports a failure to load', async () => {
    fail = true;
    renderWithProviders(<SupervisorsPanel />);
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Failed to load supervisors'
    );
  });

  it('shows a loading state first', () => {
    renderWithProviders(<SupervisorsPanel />);
    expect(screen.getByText('Loading…')).toBeInTheDocument();
  });
});
