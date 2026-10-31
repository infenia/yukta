// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import {
  describe,
  it,
  expect,
  vi,
  beforeAll,
  beforeEach,
  afterAll,
  afterEach,
} from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { renderWithProviders } from '@/test/utils/testUtils';
import { installMockEventSource } from '@/test/mocks/MockEventSource';
import { DashboardView } from '../DashboardView';

// Recharts is exercised by the end-to-end specs; here the chart modules are stand-ins.
vi.mock('../RunsChart', () => ({
  default: ({ series }: { series: Record<string, unknown[]> }) => (
    <div data-testid="runs-chart">{Object.keys(series).join(',')}</div>
  ),
}));
vi.mock('../LatencyChart', () => ({
  default: ({ series }: { series: Record<string, unknown[]> }) => (
    <div data-testid="latency-chart">{Object.keys(series).join(',')}</div>
  ),
}));

const ok = (data: unknown) =>
  HttpResponse.json({
    timestamp: 't',
    status: 200,
    message: 'ok',
    data,
    path: '/',
  });

const now = Date.now();
const minute = Math.floor(now / 60_000) * 60_000;

let limits = {
  maxConcurrent: 8,
  maxPerWorkflow: 0,
  running: 3,
  queued: 1,
  queueCapacity: 100,
};
let drain = { draining: false, running: 3, queued: 1, idle: false };
let limitsPosted: string | null = null;
let limitsStatus = 200;
let drainStatus = 200;
let metricsFail = false;
let heat: unknown[] = [];
let modules: unknown[] = [];
let liveRuns: unknown[] = [];
let marks: unknown[] = [];
let actions: string[] = [];

const server = setupServer(
  http.get('/api/scheduler/limits', () => ok(limits)),
  http.post('/api/scheduler/limits', ({ request }) => {
    limitsPosted = new URL(request.url).search;
    return limitsStatus === 200
      ? ok(null)
      : HttpResponse.json({ message: 'no' }, { status: limitsStatus });
  }),
  http.get('/api/admin/drain', () => ok(drain)),
  http.post('/api/admin/drain', () => {
    if (drainStatus !== 200) {
      return HttpResponse.json({ message: 'no' }, { status: drainStatus });
    }
    drain = { ...drain, draining: true };
    return ok(drain);
  }),
  http.delete('/api/admin/drain', () =>
    drainStatus === 200
      ? ok({ ...drain, draining: false })
      : HttpResponse.json({ message: 'no' }, { status: drainStatus })
  ),
  http.get('/api/workers', () =>
    ok([
      {
        workerId: 'w1',
        pools: [],
        pluginTypes: [],
        capacity: 8,
        usedSlots: 6,
        draining: false,
        registeredAt: 't',
        leaseUntil: 't',
      },
    ])
  ),
  http.get('/api/control/heartbeats', () =>
    ok([
      {
        workflowId: 'wf',
        nodeId: 'fresh',
        executionId: 'e',
        timestamp: new Date(now - 2000).toISOString(),
      },
      {
        workflowId: 'wf',
        nodeId: 'dead',
        executionId: 'e',
        timestamp: new Date(now - 10 * 60_000).toISOString(),
      },
    ])
  ),
  http.get('/api/sessions/summaries', () =>
    ok([{ sessionId: 's1', name: 'Alpha' }])
  ),
  http.get('/api/metrics/snapshot', ({ request }) =>
    metricsFail
      ? HttpResponse.json({ message: 'down' }, { status: 500 })
      : ok({
          time: now,
          values: {
            'runs.finished': 120,
            'duration.p95': 1800,
            'llm.tokens{direction=in}|rate': 2,
            'yukta.executions.rejected{reason=queue_full}|rate': 0.05,
            'jvm.memory.used{area=heap,id=Eden}': 50e6,
            'jvm.memory.max{area=heap,id=Eden}': 100e6,
            'process.cpu.usage': 0.25,
            'jvm.threads.live': 40,
            scoped: request.url.includes('scope') ? 1 : 0,
          },
        })
  ),
  http.get('/api/metrics/series', ({ request }) => {
    const names = new URL(request.url).searchParams.get('names') ?? '';
    return ok({
      range: '1h',
      series: names.startsWith('runs')
        ? {
            'runs.finished': [{ time: minute, value: 4 }],
            'runs.SUCCESS': [{ time: minute, value: 3 }],
          }
        : names.startsWith('duration')
          ? { 'duration.p95': [{ time: minute, value: 1800 }] }
          : {},
    });
  }),
  http.get('/api/metrics/heatmap', () => ok(heat)),
  http.get('/api/metrics/modules', () => ok(modules)),
  http.get('/api/metrics/annotations', () => ok(marks)),
  http.get('/api/metrics/runs/live', () => ok(liveRuns)),
  http.post('/api/executions/:id/:action', ({ params }) => {
    actions.push(`${String(params.action)} ${String(params.id)}`);
    return ok({ executionId: String(params.id) });
  })
);

beforeAll(() => server.listen());
beforeEach(() => installMockEventSource());
afterEach(() => {
  window.history.pushState({}, '', '/');
  server.resetHandlers();
  limits = { ...limits, maxConcurrent: 8 };
  drain = { draining: false, running: 3, queued: 1, idle: false };
  limitsPosted = null;
  limitsStatus = 200;
  drainStatus = 200;
  metricsFail = false;
  heat = [];
  modules = [];
  liveRuns = [];
  marks = [];
  actions = [];
});
afterAll(() => server.close());

describe('the Mission Control dashboard', () => {
  it('shows the command strip from the snapshot, the heat map and the workers', async () => {
    heat = [
      { time: minute, runs: 10, failed: 1, failedRuns: ['x'] },
      { time: minute - 60_000, runs: 10, failed: 0, failedRuns: [] },
    ];
    renderWithProviders(<DashboardView />);

    const strip = await screen.findByTestId('command-strip');
    await waitFor(() =>
      expect(within(strip).getByText('2.0')).toBeInTheDocument()
    );
    expect(within(strip).getByText('1.8 s')).toBeInTheDocument();
    expect(within(strip).getAllByText('95%').length).toBeGreaterThan(0); // 19 of 20 in 24 h
    expect(within(strip).getByText('3 / 1')).toBeInTheDocument();
    expect(within(strip).getByText('6 / 8')).toBeInTheDocument();
    expect(within(strip).getByText('120')).toBeInTheDocument(); // 2 tokens/s
  });

  it('draws the throughput and latency charts from the series', async () => {
    renderWithProviders(<DashboardView />);

    expect(await screen.findByTestId('runs-chart')).toHaveTextContent(
      'runs.finished,runs.SUCCESS'
    );
    expect(await screen.findByTestId('latency-chart')).toHaveTextContent(
      'duration.p95'
    );
  });

  it('marks control commands on the throughput axis', async () => {
    marks = [
      {
        time: now - 60_000,
        executionId: 'e',
        sessionId: 's1',
        workflowId: 'wf',
        command: 'PauseWorkflowCommand',
        actor: 'guard',
        applied: true,
        reason: null,
      },
    ];
    renderWithProviders(<DashboardView />);

    expect(
      await screen.findByRole('button', {
        name: 'PauseWorkflowCommand by guard',
      })
    ).toBeInTheDocument();
  });

  it('has an empty state for every panel without data', async () => {
    renderWithProviders(<DashboardView />);

    expect(await screen.findByText('No run is going')).toBeInTheDocument();
    expect(
      await screen.findByText('No runs in the last 24 hours')
    ).toBeInTheDocument();
    expect(
      await screen.findByText('No nodes finished in this range')
    ).toBeInTheDocument();
  });

  it('shows an error with a retry where a panel cannot load', async () => {
    metricsFail = true;
    renderWithProviders(<DashboardView />);

    const alerts = await screen.findAllByRole('alert');
    expect(alerts.length).toBeGreaterThan(0);
    expect(
      screen.getAllByRole('button', { name: 'Retry' }).length
    ).toBeGreaterThan(0);
  });

  it('ranks plugins and flags the node heartbeats by age', async () => {
    modules = [
      { module: 'echo', executions: 10, failures: 5, avgMs: 250 },
      { module: 'http', executions: 2, failures: 0, avgMs: 2500 },
    ];
    renderWithProviders(<DashboardView />);

    const rows = await screen.findAllByRole('row');
    expect(within(rows[1]).getByText('echo')).toBeInTheDocument();
    expect(within(rows[1]).getByText('50%')).toBeInTheDocument();
    expect(within(rows[1]).getByText('250 ms')).toBeInTheDocument();
    expect(within(rows[2]).getByText('2.5 s')).toBeInTheDocument();
    expect(await screen.findByText(/wf \/ dead/)).toBeInTheDocument();
  });

  it('lists live runs, flags the slow one and pauses or stops from the row', async () => {
    liveRuns = [
      {
        executionId: 'run-slow',
        sessionId: 's1',
        workflowId: 'wf-a',
        startedAt: now - 90_000,
        elapsedMs: 90_000,
        nodesDone: 1,
        nodesSeen: 4,
        p95Ms: 30_000,
      },
      {
        executionId: 'run-fine',
        sessionId: 's1',
        workflowId: 'wf-b',
        startedAt: now - 1000,
        elapsedMs: 1000,
        nodesDone: 2,
        nodesSeen: 2,
        p95Ms: null,
      },
    ];
    renderWithProviders(<DashboardView />);

    const slow = (await screen.findByText('wf-a')).closest('li');
    expect(slow).toHaveAttribute('data-slow', 'true');
    expect(slow).toHaveTextContent('(slow)');
    expect(screen.getByText('wf-b').closest('li')).toHaveAttribute(
      'data-slow',
      'false'
    );
    expect(
      screen.getByRole('meter', { name: 'wf-a: 1 of 4 nodes done' })
    ).toBeInTheDocument();

    const user = userEvent.setup();
    await user.click(
      screen.getByRole('button', { name: 'pause wf-a run run-slow' })
    );
    await user.click(
      screen.getByRole('button', { name: 'stop wf-a run run-slow' })
    );
    await waitFor(() =>
      expect(actions).toEqual(['pause run-slow', 'stop run-slow'])
    );
  });

  it('turns the limit with the keyboard, sends it, and puts it back when refused', async () => {
    limitsStatus = 403;
    renderWithProviders(<DashboardView />);
    const user = userEvent.setup();

    const knob = await screen.findByRole('slider', {
      name: /Concurrent run limit/,
    });
    expect(knob).toHaveAttribute('aria-valuenow', '8');
    knob.focus();
    await user.keyboard('{ArrowUp}');

    await waitFor(() => expect(limitsPosted).toContain('maxConcurrent=9'));
    expect(
      await screen.findByText('This needs an ADMIN key.')
    ).toBeInTheDocument();
    await waitFor(() =>
      expect(
        screen.getByRole('slider', { name: /Concurrent run limit/ })
      ).toHaveAttribute('aria-valuenow', '8')
    );
  });

  it('switches the drain on, and back when the server refuses', async () => {
    renderWithProviders(<DashboardView />);
    const user = userEvent.setup();
    const toggle = await screen.findByRole('switch', { name: /Drain/ });
    expect(toggle).not.toBeChecked();

    await user.click(toggle);
    await waitFor(() => expect(toggle).toBeChecked());

    drainStatus = 403;
    await user.click(toggle); // turn it off again; the server refuses
    expect(
      await screen.findByText('This needs an ADMIN key.')
    ).toBeInTheDocument();
    await waitFor(() => expect(toggle).toBeChecked()); // put back as it was
  });

  it('lists the refused starts per minute and keeps scope, range and live in the URL', async () => {
    window.history.pushState({}, '', '/?scope=session:s1&range=6h&live=0');
    renderWithProviders(<DashboardView />);

    expect(await screen.findByText('queue_full: 3.0/min')).toBeInTheDocument();
    expect(screen.getByRole('combobox', { name: 'Scope' })).toHaveValue(
      'session:s1'
    );
    expect(screen.getByRole('button', { name: '6h' })).toHaveAttribute(
      'aria-pressed',
      'true'
    );
    expect(screen.getByRole('button', { name: 'Paused' })).toBeInTheDocument();
    expect(
      await screen.findByRole('option', { name: 'Session Alpha' })
    ).toBeInTheDocument();
  });
});
