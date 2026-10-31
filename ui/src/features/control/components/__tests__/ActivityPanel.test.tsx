// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import {
  describe,
  it,
  expect,
  beforeAll,
  beforeEach,
  afterAll,
  afterEach,
  vi,
} from 'vitest';
import { act, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, useLocation } from 'react-router';
import { createTestQueryClient } from '@/test/utils/testUtils';
import {
  MockEventSource,
  installMockEventSource,
} from '@/test/mocks/MockEventSource';
import { ControlPage } from '../ControlPage';
import { FALLBACK_POLL_MS, FLUSH_MS } from '../../context/ControlEventsContext';
import {
  MAX_EVENTS,
  appendBounded,
  describe as describeEvent,
  type ActivityEvent,
  type Entry,
} from '../../lib/activity';

const ok = (data: unknown) =>
  HttpResponse.json({
    timestamp: 't',
    status: 200,
    message: 'ok',
    data,
    path: '/',
  });

let auditCalls = 0;
let supervisorCalls = 0;
const server = setupServer(
  http.get('/api/supervisors', () => {
    supervisorCalls++;
    return ok([]);
  }),
  http.get('/api/control/audit', () => {
    auditCalls++;
    return ok([]);
  }),
  http.get('/api/workers', () => ok([])),
  http.get('/api/scheduler/limits', () =>
    ok({
      maxConcurrent: 0,
      maxPerWorkflow: 0,
      running: 0,
      queued: 0,
      queueCapacity: 100,
    })
  ),
  http.get('/api/edition', () =>
    ok({ edition: 'PLUS', license: 'x', features: [], source: null })
  ),
  http.get('/api/control/heartbeats', () => ok([]))
);

beforeAll(() => server.listen());
beforeEach(() => installMockEventSource());
afterEach(() => {
  auditCalls = 0;
  supervisorCalls = 0;
  vi.useRealTimers();
});
afterAll(() => server.close());

const base = { rootExecutionId: 'r', depth: 0 };
const node = (
  id: string,
  status: string,
  severity: ActivityEvent['severity'] = 'INFO'
): ActivityEvent => ({
  ...base,
  type: 'NodeStatusChanged',
  severity,
  event: {
    executionId: `exec-${id}-0000`,
    timestamp: '2026-07-26T14:22:01',
    nodeId: id,
    status,
  },
});
const outcome: ActivityEvent = {
  ...base,
  type: 'ControlOutcome',
  severity: 'WARN',
  event: {
    executionId: 'exec-c-0000',
    timestamp: '2026-07-26T14:22:02',
    command: 'PAUSE',
    applied: false,
    reason: 'no such run',
  },
};

function Where() {
  const location = useLocation();
  return <output data-testid="where">{location.search}</output>;
}
function renderActivity(search = '?tab=activity') {
  return render(
    <QueryClientProvider client={createTestQueryClient()}>
      <MemoryRouter initialEntries={[`/control${search}`]}>
        <Where />
        <ControlPage />
      </MemoryRouter>
    </QueryClientProvider>
  );
}
const stream = () => MockEventSource.byUrl('/api/events/stream');
async function push(...events: ActivityEvent[]) {
  stream().open();
  await act(async () => {
    events.forEach((e) => stream().emit(e));
    await new Promise((resolve) => setTimeout(resolve, FLUSH_MS + 50));
  });
}

describe('activity helpers', () => {
  it('describes every kind of event', () => {
    const e = (
      type: string,
      event: Partial<ActivityEvent['event']>
    ): ActivityEvent => ({
      ...base,
      type,
      severity: 'INFO',
      event: { executionId: 'x', timestamp: 't', ...event },
    });
    expect(describeEvent(node('a', 'RUNNING'))).toBe('a → RUNNING');
    expect(
      describeEvent(
        e('WorkflowStatusChanged', { workflowId: 'w', status: 'SUCCESS' })
      )
    ).toBe('w → SUCCESS');
    expect(describeEvent(outcome)).toBe('PAUSE refused: no such run');
    expect(
      describeEvent(e('ControlOutcome', { command: 'STOP', applied: true }))
    ).toBe('STOP applied');
    expect(describeEvent(e('LogLine', { nodeId: 'n', line: 'hi' }))).toBe(
      'n: hi'
    );
    expect(describeEvent(e('EventsMissed', { count: 3 }))).toBe(
      '3 events missed'
    );
  });

  it('keeps at most the newest events and counts what it dropped', () => {
    const entry = (seq: number): Entry => ({ ...node('a', 'RUNNING'), seq });
    const full = Array.from({ length: MAX_EVENTS }, (_, i) => entry(i));
    const next = appendBounded(full, [
      entry(MAX_EVENTS),
      entry(MAX_EVENTS + 1),
    ]);
    expect(next.buffer).toHaveLength(MAX_EVENTS);
    expect(next.buffer[0].seq).toBe(2);
    expect(next.dropped).toBe(2);
    expect(appendBounded([], [entry(0)]).dropped).toBe(0);
  });
});

describe('ActivityPanel', () => {
  it('switches between the overview and the activity tab, keeping it in the URL', async () => {
    const user = userEvent.setup();
    renderActivity('');
    expect(await screen.findByText('No active nodes.')).toBeInTheDocument();

    await user.click(screen.getByRole('tab', { name: 'activity' }));
    expect(screen.getByTestId('where')).toHaveTextContent('tab=activity');
    expect(
      screen.getByRole('region', { name: 'Activity' })
    ).toBeInTheDocument();

    await user.click(screen.getByRole('tab', { name: 'overview' }));
    expect(screen.getByTestId('where')).not.toHaveTextContent('tab=');
    expect(await screen.findByText('No active nodes.')).toBeInTheDocument();
  });

  it('shows live events newest first, and a placeholder before any', async () => {
    renderActivity();
    expect(screen.getByText('No events yet.')).toBeInTheDocument();
    expect(screen.getByText('Reconnecting…')).toBeInTheDocument();

    await push(node('first', 'RUNNING'), node('second', 'SUCCESS'));
    const rows = within(screen.getByTestId('activity-list')).getAllByRole(
      'listitem'
    );
    expect(rows[0]).toHaveTextContent('second → SUCCESS');
    expect(rows[1]).toHaveTextContent('first → RUNNING');
    expect(screen.getByText('Live')).toBeInTheDocument();
    expect(within(rows[0]).getByRole('link')).toHaveAttribute(
      'href',
      '/runs?text=exec-second-0000'
    );
  });

  it('filters by type, severity and execution id, all in the URL', async () => {
    const user = userEvent.setup();
    renderActivity();
    await push(node('a', 'RUNNING'), node('b', 'FAILURE', 'ERROR'), outcome);

    await user.click(screen.getByRole('button', { name: 'ControlOutcome' }));
    expect(screen.getAllByRole('listitem')).toHaveLength(1);
    expect(screen.getByTestId('where')).toHaveTextContent(
      'type=ControlOutcome'
    );
    await user.click(screen.getByRole('button', { name: 'ControlOutcome' }));

    await user.click(screen.getByRole('button', { name: 'ERROR' }));
    expect(screen.getAllByRole('listitem')).toHaveLength(1);
    expect(screen.getByText('b → FAILURE')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'ERROR' }));

    await user.type(screen.getByLabelText('Execution id'), 'exec-a');
    expect(screen.getAllByRole('listitem')).toHaveLength(1);
    expect(screen.getByTestId('where')).toHaveTextContent('execution=exec-a');
  });

  it('reads its filters from the URL', async () => {
    renderActivity('?tab=activity&severity=WARN&type=ControlOutcome');
    await push(node('a', 'RUNNING'), outcome);
    expect(screen.getAllByRole('listitem')).toHaveLength(1);
    expect(screen.getByRole('button', { name: 'WARN' })).toHaveAttribute(
      'aria-pressed',
      'true'
    );
  });

  it('pauses the view, counts what arrives meanwhile and shows it on resume', async () => {
    const user = userEvent.setup();
    renderActivity();
    await push(node('a', 'RUNNING'));

    await user.click(screen.getByRole('button', { name: 'Pause' }));
    await push(node('b', 'RUNNING'), node('c', 'RUNNING'));
    expect(screen.getAllByRole('listitem')).toHaveLength(1);
    expect(screen.getByText(/2 new while paused/)).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Resume' }));
    expect(screen.getAllByRole('listitem')).toHaveLength(3);
  });

  it('pauses on an empty list and draws only the newest rows of a long one', async () => {
    const user = userEvent.setup();
    renderActivity();
    await user.click(screen.getByRole('button', { name: 'Pause' }));
    await user.click(screen.getByRole('button', { name: 'Resume' }));

    await push(
      ...Array.from({ length: 2100 }, (_, i) => node(`n${i}`, 'RUNNING'))
    );
    expect(screen.getAllByRole('listitem')).toHaveLength(200);
    expect(screen.getByText(/100 oldest dropped/)).toBeInTheDocument();
    expect(screen.getAllByRole('listitem')[0]).toHaveTextContent('n2099');
  });
});

describe('the control panels follow the stream instead of polling', () => {
  it('refetches on a ControlOutcome, and polls only slowly while the stream is down', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    renderOverview();
    await vi.waitFor(() => expect(auditCalls).toBe(1));

    act(() => stream().open());
    await act(async () => {
      await vi.advanceTimersByTimeAsync(30_000);
    });
    expect(auditCalls).toBe(1); // connected: no 5 s polling

    act(() => stream().emit(outcome));
    await vi.waitFor(() => expect(auditCalls).toBe(2));
    expect(supervisorCalls).toBe(2);

    act(() => stream().emit(node('a', 'RUNNING')));
    await act(async () => {
      await vi.advanceTimersByTimeAsync(FLUSH_MS * 2);
    });
    expect(auditCalls).toBe(2); // other events do not refetch

    act(() => stream().emitError());
    await act(async () => {
      await vi.advanceTimersByTimeAsync(FALLBACK_POLL_MS + 1000);
    });
    expect(auditCalls).toBeGreaterThanOrEqual(3);
  });
});

function renderOverview() {
  return renderActivity('');
}
