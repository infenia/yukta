// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { renderWithProviders } from '@/test/utils/testUtils';
import type { Worker } from '../../api/workers';
import { WorkersPanel } from '../WorkersPanel';
import { relativeTime } from '../WorkersPanel.helpers';

const ok = (data: unknown) =>
  HttpResponse.json({
    timestamp: 't',
    status: 200,
    message: 'ok',
    data,
    path: '/',
  });

const worker = (overrides: Partial<Worker>): Worker => ({
  workerId: 'w1',
  pools: ['default'],
  pluginTypes: ['HTTP_REQUEST'],
  capacity: 4,
  usedSlots: 1,
  draining: false,
  registeredAt: new Date(Date.now() - 600_000).toISOString(),
  leaseUntil: new Date(Date.now() + 20_000).toISOString(),
  ...overrides,
});

let workers: Worker[] = [];
let fail = false;
let edition = 'PLUS';

const server = setupServer(
  http.get('/api/workers', () =>
    fail ? HttpResponse.json({ message: 'x' }, { status: 500 }) : ok(workers)
  ),
  http.get('/api/edition', () =>
    ok({ edition, license: 'x', features: [], source: null })
  )
);

beforeAll(() => server.listen());
afterEach(() => {
  workers = [];
  fail = false;
  edition = 'PLUS';
});
afterAll(() => server.close());

describe('relativeTime', () => {
  it('uses seconds under a minute and minutes beyond', () => {
    const now = Date.parse('2026-01-01T00:00:00Z');
    expect(relativeTime('2026-01-01T00:00:20Z', now)).toBe('in 20 seconds');
    expect(relativeTime('2025-12-31T23:59:50Z', now)).toBe('10 seconds ago');
    expect(relativeTime('2026-01-01T00:03:00Z', now)).toBe('in 3 minutes');
  });
});

describe('WorkersPanel', () => {
  it('lists workers with pools, types, capacity use and lease', async () => {
    workers = [
      worker({
        workerId: 'gpu-1',
        pools: ['gpu', 'default'],
        capacity: 8,
        usedSlots: 6,
      }),
      worker({ workerId: 'busy', draining: true }),
    ];
    renderWithProviders(<WorkersPanel />);
    const row = (await screen.findByText('gpu-1')).closest('tr') as HTMLElement;
    expect(within(row).getByText('gpu')).toBeInTheDocument();
    expect(within(row).getByText('6/8')).toBeInTheDocument();
    expect(within(row).getByLabelText('gpu-1 slots in use')).toHaveAttribute(
      'value',
      '6'
    );
    expect(within(row).getByText(/in \d+ seconds/)).toBeInTheDocument();
    expect(screen.getByText(/draining/)).toBeInTheDocument();
  });

  it('collapses plugin types beyond three into +n', async () => {
    workers = [worker({ pluginTypes: ['A', 'B', 'C', 'D', 'E'] })];
    renderWithProviders(<WorkersPanel />);
    const more = await screen.findByText('+2');
    expect(more).toHaveAttribute('title', 'D, E');
    expect(screen.queryByText('D')).not.toBeInTheDocument();
  });

  it('guards against a worker with no capacity', async () => {
    workers = [worker({ capacity: 0, usedSlots: 0 })];
    renderWithProviders(<WorkersPanel />);
    expect(await screen.findByText('0/0')).toBeInTheDocument();
  });

  it('says workers need the Plus edition on Community', async () => {
    edition = 'COMMUNITY';
    renderWithProviders(<WorkersPanel />);
    expect(
      await screen.findByText(/need the Plus edition/)
    ).toBeInTheDocument();
  });

  it('explains how to connect a worker on other editions', async () => {
    renderWithProviders(<WorkersPanel />);
    expect(await screen.findByText(/Remote workers/)).toBeInTheDocument();
    expect(screen.getByText('No workers connected.')).toBeInTheDocument();
  });

  it('reports a failure and retries', async () => {
    fail = true;
    const user = userEvent.setup();
    renderWithProviders(<WorkersPanel />);
    expect(
      await screen.findByText('Failed to load workers')
    ).toBeInTheDocument();
    fail = false;
    workers = [worker({ workerId: 'back' })];
    await user.click(screen.getByRole('button', { name: /retry/i }));
    expect(await screen.findByText('back')).toBeInTheDocument();
  });
});
