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
  groupOf,
  matches,
  typesOf,
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
  http.get('/api/supervision/summary', () => {
    supervisorCalls++;
    return ok({ unowned: 0, unownedWorkflows: [] });
  }),
  http.get('/api/supervisors/decisions', () => ok([])),
  http.get('/api/supervisors/assignments', () => ok([])),
  http.get('/api/supervisors', () => ok([])),
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
  )
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
/** The rows of the event table, without its header row. */
const bodyRows = () =>
  within(screen.getByTestId('activity-list')).getAllByRole('row').slice(1);
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
    expect(describeEvent(node('a', 'RUNNING'))).toBe('RUNNING');
    expect(
      describeEvent(
        e('WorkflowStatusChanged', { workflowId: 'w', status: 'SUCCESS' })
      )
    ).toBe('SUCCESS');
    expect(describeEvent(outcome)).toBe('PAUSE refused: no such run');
    expect(
      describeEvent(e('ControlOutcome', { command: 'STOP', applied: true }))
    ).toBe('STOP applied');
    expect(describeEvent(e('LogLine', { nodeId: 'n', line: 'hi' }))).toBe('hi');
    expect(describeEvent(e('EventsMissed', { count: 3 }))).toBe(
      '3 events missed'
    );
    const request = {
      nodeId: 'ask',
      tier: 'ops',
      spec: { kind: 'APPROVAL', risk: 'HIGH' },
    };
    expect(describeEvent(e('InputRequested', { request }))).toBe(
      'ask asked for input (APPROVAL, HIGH risk)'
    );
    expect(describeEvent(e('InputEscalated', { request }))).toBe(
      'input of ask escalated to ops'
    );
    expect(describeEvent(e('InputAnswered', { decision: 'approve' }))).toBe(
      'input answered: approve'
    );
    expect(describeEvent(e('InputExpired', {}))).toBe('input request expired');
    expect(describeEvent(e('InputCancelled', { reason: 'run-ended' }))).toBe(
      'input request cancelled: run-ended'
    );
  });

  it('groups the event types and matches a run, workflow or node by text', () => {
    expect(groupOf('NodeStatusChanged')).toBe('nodes');
    expect(groupOf('InputExpired')).toBe('inputs');
    expect(groupOf('Unknown')).toBeUndefined();
    expect(typesOf(['runs', 'logs'])).toEqual([
      'WorkflowStatusChanged',
      'LogLine',
    ]);
    const e: ActivityEvent = {
      ...base,
      type: 'NodeStatusChanged',
      severity: 'INFO',
      event: {
        executionId: 'Exec-1',
        timestamp: 't',
        workflowId: 'Build',
        nodeId: 'Lint',
      },
    };
    const f = (text: string) => ({ types: [], severities: [], text });
    expect(matches(e, f(''))).toBe(true);
    expect(matches(e, f('exec'))).toBe(true);
    expect(matches(e, f('build'))).toBe(true);
    expect(matches(e, f('LINT'))).toBe(true);
    expect(matches(e, f('deploy'))).toBe(false);
    // an event without a workflow or node only matches on its run
    expect(matches(node('x', 'RUNNING'), f('build'))).toBe(false);
    // chosen types and severities narrow the match
    const g = { types: ['NodeStatusChanged'], severities: ['INFO'], text: '' };
    expect(matches(e, { ...g, types: [] })).toBe(true);
    expect(matches(e, { ...g, types: ['ControlOutcome'] })).toBe(false);
    expect(matches(e, { ...g, severities: [] })).toBe(true);
    expect(matches(e, { ...g, severities: ['ERROR'] })).toBe(false);
    expect(matches(e, g)).toBe(true);
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
    expect(
      await screen.findByRole('heading', { name: 'Capacity' })
    ).toBeInTheDocument();

    await user.click(screen.getByRole('tab', { name: 'Activity' }));
    expect(screen.getByTestId('where')).toHaveTextContent('tab=activity');
    expect(
      screen.getByRole('region', { name: 'Activity' })
    ).toBeInTheDocument();

    await user.click(screen.getByRole('tab', { name: 'Overview' }));
    expect(screen.getByTestId('where')).not.toHaveTextContent('tab=');
    expect(
      await screen.findByRole('heading', { name: 'Capacity' })
    ).toBeInTheDocument();
  });

  it('shows live events newest first as a table, and a placeholder before any', async () => {
    renderActivity();
    expect(screen.getByText(/No events yet\./)).toBeInTheDocument();
    expect(screen.getByText('Reconnecting…')).toBeInTheDocument();

    await push(node('first', 'RUNNING'), node('second', 'SUCCESS'));
    const rows = bodyRows();
    expect(rows[0]).toHaveTextContent('second');
    expect(rows[0]).toHaveTextContent('SUCCESS');
    expect(rows[0]).toHaveTextContent('Nodes');
    expect(rows[1]).toHaveTextContent('first');
    expect(screen.getByText('Live')).toBeInTheDocument();
    // no session known: the link searches the runs
    expect(within(rows[0]).getByRole('link')).toHaveAttribute(
      'href',
      '/runs?text=exec-second-0000'
    );
  });

  it('names the workflow and links to the run when the session is known', async () => {
    renderActivity();
    await push({
      ...base,
      type: 'WorkflowStatusChanged',
      severity: 'INFO',
      event: {
        executionId: 'exec-w-0000',
        timestamp: '2026-07-26T14:22:01',
        sessionId: 's1',
        workflowId: 'build',
        status: 'RUNNING',
      },
    });
    const row = bodyRows()[0];
    expect(row).toHaveTextContent('build');
    expect(row).toHaveTextContent('Runs');
    expect(within(row).getByRole('link')).toHaveAttribute(
      'href',
      '/sessions/s1/workflow/build?exec=exec-w-0000'
    );
  });

  it('hides the log lines until Logs is turned on', async () => {
    const user = userEvent.setup();
    renderActivity();
    await push(node('a', 'RUNNING'), {
      ...base,
      type: 'LogLine',
      severity: 'INFO',
      event: {
        executionId: 'exec-l-0000',
        timestamp: '2026-07-26T14:22:03',
        nodeId: 'a',
        line: 'compiling',
      },
    });
    expect(bodyRows()).toHaveLength(1);
    // the chip counts what it would add
    expect(screen.getByRole('button', { name: /^Logs/ })).toHaveTextContent(
      '1'
    );

    await user.click(screen.getByRole('button', { name: /^Logs/ }));
    expect(bodyRows()).toHaveLength(2);
    expect(screen.getByText('compiling')).toBeInTheDocument();
    expect(screen.getByTestId('where')).toHaveTextContent('group=logs');
  });

  it('filters by kind, severity and text, all in the URL', async () => {
    const user = userEvent.setup();
    renderActivity();
    await push(node('a', 'RUNNING'), node('b', 'FAILURE', 'ERROR'), outcome);

    // turning Nodes off leaves the control outcome
    await user.click(screen.getByRole('button', { name: /^Nodes/ }));
    expect(bodyRows()).toHaveLength(1);
    expect(screen.getByTestId('where')).not.toHaveTextContent('group=nodes');
    await user.click(screen.getByRole('button', { name: /^Nodes/ }));
    expect(bodyRows()).toHaveLength(3);

    await user.click(screen.getByRole('button', { name: 'ERROR' }));
    expect(bodyRows()).toHaveLength(1);
    expect(screen.getByText('FAILURE')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'ERROR' }));

    const box = screen.getByLabelText('Run, workflow or node');
    await user.type(box, 'exec-a');
    expect(bodyRows()).toHaveLength(1);
    expect(screen.getByTestId('where')).toHaveTextContent('q=exec-a');
    await user.clear(box);
    expect(screen.getByTestId('where')).not.toHaveTextContent('q=');
    expect(bodyRows()).toHaveLength(3);
  });

  it('reads its filters from the URL, an older run link included', async () => {
    renderActivity('?tab=activity&severity=WARN&group=control');
    await push(node('a', 'RUNNING'), outcome);
    expect(bodyRows()).toHaveLength(1);
    expect(screen.getByRole('button', { name: 'WARN' })).toHaveAttribute(
      'aria-pressed',
      'true'
    );
    expect(screen.getByRole('button', { name: /^Control/ })).toHaveAttribute(
      'aria-pressed',
      'true'
    );
  });

  it('takes the run of an older link into the text box', () => {
    renderActivity('?tab=activity&execution=exec-9');
    expect(screen.getByLabelText('Run, workflow or node')).toHaveValue(
      'exec-9'
    );
  });

  it('pauses the view, counts what arrives meanwhile and shows it on resume', async () => {
    const user = userEvent.setup();
    renderActivity();
    await push(node('a', 'RUNNING'));

    await user.click(screen.getByRole('button', { name: 'Pause' }));
    await push(node('b', 'RUNNING'), node('c', 'RUNNING'));
    expect(bodyRows()).toHaveLength(1);
    expect(screen.getByText(/2 new while paused/)).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Resume' }));
    expect(bodyRows()).toHaveLength(3);
  });

  it('pauses on an empty list and draws only the newest rows of a long one', async () => {
    const user = userEvent.setup();
    renderActivity();
    await user.click(screen.getByRole('button', { name: 'Pause' }));
    await user.click(screen.getByRole('button', { name: 'Resume' }));

    await push(
      ...Array.from({ length: 2100 }, (_, i) => node(`n${i}`, 'RUNNING'))
    );
    expect(bodyRows()).toHaveLength(200);
    expect(screen.getByText(/100 oldest dropped/)).toBeInTheDocument();
    expect(bodyRows()[0]).toHaveTextContent('n2099');
  });

  it('says that log lines are hidden when only they would fill an empty list', () => {
    renderActivity();
    expect(
      screen.getByText(/Log lines are hidden: turn on Logs/)
    ).toBeInTheDocument();
  });

  it('does not mention hidden logs when Logs is on', () => {
    renderActivity('?tab=activity&group=logs');
    expect(screen.queryByText(/Log lines are hidden/)).toBeNull();
  });
});

describe('the control panels follow the stream instead of polling', () => {
  it('refetches on a ControlOutcome, and polls only slowly while the stream is down', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    renderActivity('?tab=audit');
    await vi.waitFor(() => expect(auditCalls).toBe(1));

    act(() => stream().open());
    await act(async () => {
      await vi.advanceTimersByTimeAsync(30_000);
    });
    expect(auditCalls).toBe(1); // connected: no 5 s polling

    act(() => stream().emit(outcome));
    await vi.waitFor(() => expect(auditCalls).toBe(2));

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
    // advances about 40 s of fake time; on a loaded machine with coverage that takes more than 5 s
  }, 20_000);

  it('refetches the supervisors of the overview on a ControlOutcome', async () => {
    renderActivity('');
    await vi.waitFor(() => expect(supervisorCalls).toBe(1));
    act(() => stream().open());
    act(() => stream().emit(outcome));
    await vi.waitFor(() => expect(supervisorCalls).toBe(2));
  });
});
