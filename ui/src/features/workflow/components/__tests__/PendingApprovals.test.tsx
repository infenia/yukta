// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import {
  createTestQueryClient,
  renderWithProviders,
} from '@/test/utils/testUtils';
import { PendingApprovals } from '../PendingApprovals';
import type { PendingApproval } from '../../api/approvals';

const ok = (data: unknown) =>
  HttpResponse.json({
    timestamp: 't',
    status: 200,
    message: 'ok',
    data,
    path: '/',
  });

let approvals: PendingApproval[] = [];
let listFails = false;
let signalStatus = 200;
const signals: string[] = [];

const server = setupServer(
  http.get('/api/sessions/s1/approvals', () => {
    return listFails
      ? HttpResponse.json({ message: 'x' }, { status: 500 })
      : ok(approvals);
  }),
  http.post('/api/executions/:id/nodes/:node/signal', ({ params, request }) => {
    signals.push(`${params.id} ${params.node} ${new URL(request.url).search}`);
    if (signalStatus !== 200) {
      return HttpResponse.json(
        { message: 'Node is not waiting' },
        { status: signalStatus }
      );
    }
    approvals = [];
    return ok({ executionId: String(params.id) });
  })
);

beforeAll(() => server.listen());
afterEach(() => {
  approvals = [];
  listFails = false;
  signalStatus = 200;
  signals.length = 0;
});
afterAll(() => server.close());

const waiting = (over: Partial<PendingApproval> = {}): PendingApproval => ({
  executionId: 'abcdef123456',
  workflowId: 'wf',
  nodeId: 'gate',
  name: 'approval',
  since: '2026-10-14T10:00:00Z',
  ...over,
});

describe('PendingApprovals', () => {
  it('shows nothing when no step waits, or the list cannot be read', async () => {
    const { container, unmount } = renderWithProviders(
      <PendingApprovals sessionId="s1" workflowId="wf" />
    );
    await waitFor(() => expect(container).toBeEmptyDOMElement());
    unmount();

    listFails = true;
    const failed = renderWithProviders(
      <PendingApprovals sessionId="s1" workflowId="wf" />
    );
    await waitFor(() => expect(failed.container).toBeEmptyDOMElement());
  });

  it('lists the steps of this workflow only, and approves one', async () => {
    approvals = [
      waiting(),
      waiting({ workflowId: 'other', nodeId: 'elsewhere' }),
    ];
    renderWithProviders(<PendingApprovals sessionId="s1" workflowId="wf" />);

    expect(await screen.findByText('gate')).toBeInTheDocument();
    expect(screen.queryByText('elsewhere')).not.toBeInTheDocument();
    expect(screen.getByText('abcdef12')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Approve' }));

    await waitFor(() =>
      expect(signals).toEqual([
        'abcdef123456 gate ?name=approval&decision=approve',
      ])
    );
    await waitFor(() =>
      expect(screen.queryByText('gate')).not.toBeInTheDocument()
    );
  });

  it('rejects, and shows why a decision was not accepted', async () => {
    approvals = [waiting()];
    signalStatus = 409;
    renderWithProviders(<PendingApprovals sessionId="s1" workflowId="wf" />);

    await userEvent.click(
      await screen.findByRole('button', { name: 'Reject' })
    );

    expect(await screen.findByText('Node is not waiting')).toBeInTheDocument();
    expect(signals[0]).toContain('decision=reject');
  });

  it('polls while the run is going, and not when it has ended', async () => {
    const interval = (live: boolean) => {
      const queryClient = createTestQueryClient();
      const view = renderWithProviders(
        <PendingApprovals sessionId="s1" workflowId="wf" live={live} />,
        { queryClient }
      );
      const query = queryClient.getQueryCache().find({
        queryKey: ['approvals', 's1'],
      });
      const value = query?.observers[0].options.refetchInterval;
      view.unmount();
      return value;
    };
    expect(interval(true)).toBe(5000);
    expect(interval(false)).toBe(false);
  });
});
