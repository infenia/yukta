// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import {
  describe,
  it,
  expect,
  beforeAll,
  afterAll,
  afterEach,
  beforeEach,
} from 'vitest';
import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { renderWithProviders } from '@/test/utils/testUtils';
import {
  MockEventSource,
  installMockEventSource,
} from '@/test/mocks/MockEventSource';
import { FLUSH_MS } from '@/features/control/context/ControlEventsContext';
import { OverviewPage } from '../OverviewPage';

const ok = (data: unknown) =>
  HttpResponse.json({
    timestamp: 't',
    status: 200,
    message: 'ok',
    data,
    path: '/',
  });

const approval = {
  sessionId: 's1',
  executionId: 'exec-1234-5678',
  workflowId: 'deploy',
  nodeId: 'gate',
  name: 'go',
  since: '2026-01-01T00:00:00Z',
};
const run = (status: string, executionId: string) => ({
  sessionId: 's1',
  workflowId: 'deploy',
  executionId,
  status,
  startTime: null,
  endTime: null,
});

let approvals: unknown[] = [approval];
let aborted: unknown[] = [run('ABORTED', 'ab-1')];
let failures: unknown[] = [run('FAILURE', 'f-1')];

const server = setupServer(
  http.get('/api/approvals', () => ok(approvals)),
  http.get('/api/executions/counts', () =>
    ok({ running: 2, queued: 1, failed: 3 })
  ),
  http.get('/api/executions', ({ request }) =>
    ok(
      new URL(request.url).searchParams.get('status') === 'ABORTED'
        ? aborted
        : failures
    )
  ),
  http.get('/api/scheduler/limits', () =>
    ok({
      maxConcurrent: 4,
      maxPerWorkflow: 0,
      running: 2,
      queued: 1,
      queueCapacity: 10,
    })
  ),
  http.get('/api/workers', () => ok([{ workerId: 'w1' }])),
  http.get('/api/edition', () =>
    ok({ edition: 'PLUS', license: 'x', features: [], source: null })
  ),
  http.post('/api/executions/:id/recover', () => ok({ executionId: 'new-run' }))
);

beforeAll(() => server.listen());
beforeEach(() => installMockEventSource());
afterEach(() => {
  approvals = [approval];
  aborted = [run('ABORTED', 'ab-1')];
  failures = [run('FAILURE', 'f-1')];
  window.history.pushState({}, '', '/');
});
afterAll(() => server.close());

describe('OverviewPage', () => {
  it('shows the counts, limits and worker count in the tiles', async () => {
    renderWithProviders(<OverviewPage />);
    expect(await screen.findByText('limit 4')).toBeInTheDocument();
    expect(screen.getByText('of 10')).toBeInTheDocument();
    expect(
      screen.getByRole('link', { name: /Failed in 24 h/ })
    ).toHaveTextContent('3');
    expect(
      screen.getByRole('link', { name: /Workers online/ })
    ).toHaveTextContent('1');
    const tile = screen.getByRole('link', { name: /Inputs waiting/ });
    expect(tile).toHaveTextContent('1');
    // it jumps to the panel on this page
    expect(tile).toHaveAttribute('href', '#ov-needs-attention-panel');
    expect(
      screen.getByRole('region', { name: 'Needs attention' })
    ).toHaveAttribute('id', 'ov-needs-attention-panel');
  });

  it('links a waiting input to its run and lists aborted runs and failures', async () => {
    renderWithProviders(<OverviewPage />);
    await waitFor(() =>
      expect(screen.getAllByRole('link', { name: 's1/deploy' })).toHaveLength(3)
    );
    expect(screen.getByText(/open the run to answer/)).toBeInTheDocument();
  });

  it('recovers an aborted run and opens the new one', async () => {
    const user = userEvent.setup();
    renderWithProviders(<OverviewPage />);
    await user.click(await screen.findByRole('button', { name: /Recover/ }));
    const dialog = screen.getByRole('dialog');
    await user.click(within(dialog).getByRole('button', { name: 'Recover' }));
    await waitFor(() => expect(window.location.search).toBe('?exec=new-run'));
  });

  it('says so when nothing needs attention or failed, and without a limit', async () => {
    approvals = [];
    aborted = [];
    failures = [];
    server.use(
      http.get('/api/scheduler/limits', () =>
        ok({
          maxConcurrent: 0,
          maxPerWorkflow: 0,
          running: 0,
          queued: 0,
          queueCapacity: 0,
        })
      )
    );
    renderWithProviders(<OverviewPage />);
    expect(
      await screen.findByText('Nothing is waiting for you.')
    ).toBeInTheDocument();
    expect(
      await screen.findByText('No failures in 24 hours.')
    ).toBeInTheDocument();
    expect(screen.queryByText(/^limit/)).not.toBeInTheDocument();
  });

  it('follows the event stream', async () => {
    renderWithProviders(<OverviewPage />);
    expect(
      await screen.findByText('Connecting to the event stream…')
    ).toBeInTheDocument();
    const stream = MockEventSource.byUrl('/api/events/stream');
    act(() => stream.open());
    expect(await screen.findByText('Waiting for events.')).toBeInTheDocument();
    await act(async () => {
      stream.emit({
        type: 'WorkflowStatusChanged',
        severity: 'INFO',
        rootExecutionId: 'r',
        depth: 0,
        event: {
          executionId: 'r',
          timestamp: 't',
          workflowId: 'deploy',
          status: 'RUNNING',
        },
      });
      await new Promise((resolve) => setTimeout(resolve, FLUSH_MS + 50));
    });
    expect(screen.getByText('deploy → RUNNING')).toBeInTheDocument();
  });
});
