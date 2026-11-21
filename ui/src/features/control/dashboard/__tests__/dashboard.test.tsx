// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { choose } from '@/test/utils/choose';
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
import { setMe } from '@/lib/identity';
import { installMockEventSource } from '@/test/mocks/MockEventSource';
import { DashboardView } from '../DashboardView';

// Recharts is exercised by the end-to-end specs; here the chart modules are stand-ins.
vi.mock('../RunsChart', () => ({
  default: ({ series }: { series: Record<string, unknown[]> }) => (
    <div data-testid="runs-chart">{Object.keys(series).join(',')}</div>
  ),
}));
vi.mock('../OverheadChart', () => ({
  default: ({ series }: { series: Record<string, unknown[]> }) => (
    <div data-testid="overhead-chart">{Object.keys(series).join(',')}</div>
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
let nodes: unknown[] = [];
let liveRuns: unknown[] = [];
let marks: unknown[] = [];
let actions: string[] = [];
let approvals: unknown[] = [];

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
  http.get('/api/sessions/summaries', () =>
    ok([
      {
        sessionId: 's1',
        name: 'Alpha',
        lastRun: { executionId: 'x', workflowId: 'wf-last', status: 'SUCCESS' },
      },
    ])
  ),
  http.get('/api/sessions/:id', ({ params }) =>
    ok({ sessionId: String(params.id), name: 'Alpha', workflowIds: ['wf-own'] })
  ),
  http.get('/api/inputs', () => ok(approvals)),
  http.get('/api/metrics/snapshot', ({ request }) =>
    metricsFail
      ? HttpResponse.json({ message: 'down' }, { status: 500 })
      : ok({
          time: now,
          values: {
            'runs.finished': 120,
            'runs.failed': 3,
            'overhead.start.p95': 12,
            'overhead.handoff.p95': 4,
            'llm.tokens{direction=input,provider=OPENAI}|rate': 2,
            'llm.tokens{direction=output,provider=OPENAI}|rate': 1,
            'yukta.mcp.heavy.refused|rate': 0.1,
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
        : names.startsWith('overhead')
          ? {
              'overhead.start.p95': [
                { time: minute - 60_000, value: 6 },
                { time: minute, value: 12 },
              ],
              'overhead.handoff.p95': [{ time: minute, value: 4 }],
            }
          : names.startsWith('llm.cost')
            ? {
                'llm.cost{provider=OPENAI}|rate': [
                  { time: minute - 10_000, value: 0 },
                  { time: minute, value: 0.05 },
                ],
              }
            : {},
    });
  }),
  http.get('/api/metrics/heatmap', () => ok(heat)),
  http.get('/api/metrics/modules', () => ok(modules)),
  http.get('/api/metrics/nodes', () => ok(nodes)),
  http.get('/api/edition', () =>
    ok({ edition: 'PLUS', license: 'x', features: [], source: null })
  ),
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
  nodes = [];
  liveRuns = [];
  marks = [];
  actions = [];
  approvals = [];
});
afterAll(() => server.close());

describe('the Mission Control dashboard', () => {
  it('shows the command strip from the snapshot, the day band and the workers', async () => {
    heat = [
      { time: minute, runs: 10, failed: 1, failedRuns: ['x'] },
      { time: minute - 60_000, runs: 10, failed: 0, failedRuns: [] },
    ];
    renderWithProviders(<DashboardView />);

    const strip = await screen.findByTestId('command-strip');
    await waitFor(() =>
      expect(within(strip).getByText('of 8')).toBeInTheDocument()
    );
    expect(within(strip).getByText('holds 100')).toBeInTheDocument();
    expect(within(strip).getAllByText('95%').length).toBeGreaterThan(0); // 19 of 20 in 24 h
    expect(within(strip).getByText('of 120 runs')).toBeInTheDocument();
    expect(within(strip).getByText('12 ms')).toBeInTheDocument(); // run start p95
    expect(within(strip).getByText('+100%')).toBeInTheDocument(); // 6 ms to 12 ms
    expect(
      within(strip).getByText('request to plugin · hand-off 4 ms')
    ).toBeInTheDocument();
    expect(within(strip).getByText('0')).toBeInTheDocument(); // no inputs waiting
    expect(await screen.findByText('20 runs')).toBeInTheDocument();
    expect(
      await screen.findByRole('meter', { name: 'w1: 6 of 8 slots' })
    ).toBeInTheDocument();
  });

  it('draws the throughput and engine overhead charts from the series', async () => {
    renderWithProviders(<DashboardView />);

    expect(await screen.findByTestId('runs-chart')).toHaveTextContent(
      'runs.finished,runs.SUCCESS'
    );
    expect(await screen.findByTestId('overhead-chart')).toHaveTextContent(
      'overhead.handoff.p95'
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
    expect(await screen.findByText('0 runs')).toBeInTheDocument();
    // the slowest nodes and the plugins each say so in one line
    expect(
      await screen.findAllByText('No nodes finished in this range')
    ).toHaveLength(2);
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

  it('ranks plugins by executions with their failures, and names the slowest nodes', async () => {
    modules = [
      { module: 'echo', executions: 10, failures: 5, avgMs: 250 },
      { module: 'http', executions: 2, failures: 0, avgMs: 2500 },
    ];
    nodes = [
      {
        sessionId: 's1',
        workflowId: 'wf-a',
        nodeId: 'build',
        module: 'PROCESS_EXECUTOR',
        runs: 3,
        failures: 0,
        p50Ms: 90_000,
        p95Ms: 400_000,
        lastExecutionId: 'r9',
      },
    ];
    renderWithProviders(<DashboardView />);

    const build = await screen.findByRole('link', { name: 'build' });
    expect(build).toHaveAttribute(
      'href',
      '/sessions/s1/workflow/wf-a?exec=r9&node=build'
    );
    expect(await screen.findByText('6 m 40 s')).toBeInTheDocument(); // p95 of that node
    const echo = (await screen.findByText('echo')).closest('tr');
    expect(echo).toHaveTextContent('10');
    expect(echo).toHaveTextContent('5');
    expect(screen.queryByText('2.5 s')).toBeNull(); // no mean per plugin type
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
        paused: false,
        currentNodes: ['build'],
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
        paused: false,
        currentNodes: [] as string[],
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
    expect(screen.getByText('build')).toBeInTheDocument(); // the node running now
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
    // stopping asks first
    expect(actions).toEqual(['pause run-slow']);
    await user.click(screen.getByRole('button', { name: 'Stop run' }));
    await waitFor(() =>
      expect(actions).toEqual(['pause run-slow', 'stop run-slow'])
    );
  });

  it('shows only the live runs of the chosen scope', async () => {
    const run = (
      executionId: string,
      sessionId: string,
      workflowId: string
    ) => ({
      executionId,
      sessionId,
      workflowId,
      startedAt: now,
      elapsedMs: 1000,
      nodesDone: 0,
      nodesSeen: 1,
      p95Ms: null,
      paused: false,
      currentNodes: [] as string[],
    });
    liveRuns = [run('r1', 's1', 'wf-a'), run('r2', 's2', 'wf-b')];
    window.history.pushState({}, '', '/?scope=session:s1');
    renderWithProviders(<DashboardView />);

    expect(await screen.findByText('wf-a')).toBeInTheDocument();
    expect(screen.queryByText('wf-b')).toBeNull();
  });

  it('shows the limit knob, the drain switch and the run buttons disabled to a viewer', async () => {
    setMe({
      authenticated: true,
      keyName: 'v',
      role: 'VIEWER',
      sessions: null,
      authEnabled: true,
      permissions: {
        startRuns: false,
        controlRuns: false,
        editSessions: false,
        deleteSessions: false,
        changeLimits: false,
        sendCommands: false,
      },
    });
    liveRuns = [
      {
        executionId: 'r1',
        sessionId: 's1',
        workflowId: 'wf-a',
        startedAt: now,
        elapsedMs: 1000,
        nodesDone: 0,
        nodesSeen: 1,
        p95Ms: null,
        paused: false,
        currentNodes: [] as string[],
      },
    ];
    renderWithProviders(<DashboardView />);

    expect(await screen.findByRole('switch', { name: /Drain/ })).toBeDisabled();
    expect(
      screen.getByRole('slider', { name: /Concurrent run limit/ })
    ).toHaveAttribute('aria-disabled', 'true');
    expect(
      await screen.findByRole('button', { name: 'stop wf-a run r1' })
    ).toBeDisabled();
    setMe(null);
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
    // draining refuses every new start, so it asks first
    expect(toggle).not.toBeChecked();
    await user.click(screen.getByRole('button', { name: 'Drain' }));
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
    await waitFor(() =>
      expect(screen.getByRole('combobox', { name: 'Scope' })).toHaveTextContent(
        'Session Alpha'
      )
    );
    expect(screen.getByRole('button', { name: '6h' })).toHaveAttribute(
      'aria-pressed',
      'true'
    );
    expect(screen.getByRole('button', { name: 'Paused' })).toBeInTheDocument();
    await userEvent.click(screen.getByRole('combobox', { name: 'Scope' }));
    expect(
      await screen.findByRole('option', { name: 'Session Alpha' })
    ).toBeInTheDocument();
  });
  it('counts the approvals waiting in the chosen scope', async () => {
    const wait = (sessionId: string, workflowId: string, nodeId: string) => ({
      sessionId,
      workflowId,
      nodeId,
      executionId: 'e',
      name: 'approve',
      since: 't',
    });
    approvals = [wait('s1', 'wf-a', 'n1'), wait('s2', 'wf-b', 'n2')];
    window.history.pushState({}, '', '/?scope=session:s1');
    renderWithProviders(<DashboardView />);

    expect(
      await screen.findByRole('link', { name: /Inputs waiting: 1\./ })
    ).toBeInTheDocument();
  });

  it('lists sessions and workflows in the scope filter and keeps the choice in the URL', async () => {
    window.history.pushState({}, '', '/?scope=session:s1');
    renderWithProviders(<DashboardView />);
    const user = userEvent.setup();

    // the chosen session's workflows and each session's last run
    await user.click(await screen.findByRole('combobox', { name: 'Scope' }));
    await screen.findByRole('option', { name: 'Workflow wf-own' });
    expect(
      screen.getByRole('option', { name: 'Workflow wf-last' })
    ).toBeInTheDocument();
    await user.keyboard('{Escape}');
    await choose(
      user,
      screen.getByRole('combobox', { name: 'Scope' }),
      'workflow:wf-own'
    );
    await waitFor(() =>
      expect(window.location.search).toContain('scope=workflow%3Awf-own')
    );
  });

  it('shows model use per provider and the refused heavy calls', async () => {
    renderWithProviders(<DashboardView />);

    const panel = (
      await screen.findByRole('heading', { name: 'AI usage' })
    ).closest('section') as HTMLElement;
    const row = await within(panel).findByRole('row', { name: /OPENAI/ });
    expect(within(row).getByText('120')).toBeInTheDocument(); // 2 tokens/s in
    expect(within(row).getByText('60')).toBeInTheDocument(); // 1 token/s out
    expect(within(panel).getByText('6.00')).toBeInTheDocument(); // refused/min
  });

  it('says the AI usage is the whole server in a scoped view', async () => {
    window.history.pushState({}, '', '/?scope=workflow:wf');
    renderWithProviders(<DashboardView />);

    expect(
      await screen.findByRole('heading', { name: 'AI usage · whole server' })
    ).toBeInTheDocument();
  });

  it('resumes a live run from its row and links to that run', async () => {
    liveRuns = [
      {
        executionId: 'r1',
        sessionId: 's1',
        workflowId: 'wf-a',
        startedAt: now,
        elapsedMs: 1000,
        nodesDone: 0,
        nodesSeen: 1,
        p95Ms: null,
        paused: true,
        currentNodes: [] as string[],
      },
    ];
    renderWithProviders(<DashboardView />);
    const user = userEvent.setup();

    expect(await screen.findByRole('link', { name: 'wf-a' })).toHaveAttribute(
      'href',
      '/sessions/s1/workflow/wf-a?exec=r1'
    );
    await user.click(
      screen.getByRole('button', { name: 'resume wf-a run r1' })
    );
    await waitFor(() => expect(actions).toEqual(['resume r1']));
  });

  it('opens the audit for a marked command that names no run', async () => {
    marks = [
      {
        time: now - 60_000,
        executionId: '',
        sessionId: null,
        workflowId: null,
        command: 'SetLimitsCommand',
        actor: 'ops',
        applied: false,
        reason: 'forbidden',
      },
    ];
    renderWithProviders(<DashboardView />);
    const user = userEvent.setup();

    await user.click(
      await screen.findByRole('button', {
        name: 'SetLimitsCommand by ops (refused: forbidden)',
      })
    );
    await waitFor(() => expect(window.location.search).toBe('?tab=audit'));
    expect(window.location.pathname).toBe('/control');
  });
});
