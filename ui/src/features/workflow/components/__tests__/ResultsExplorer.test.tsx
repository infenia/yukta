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
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { renderWithProviders } from '@/test/utils/testUtils';
import { installMockEventSource } from '@/test/mocks/MockEventSource';
import {
  createMockGraph,
  createMockProgress,
} from '@/test/factories/workflowFactory';
import { ProgressPanel } from '../ProgressPanel';
import { ResultsExplorer } from '../ResultsExplorer';
import type { ResultEvent } from '../../types/workflow';

const event = (
  sequence: number,
  nodeId: string,
  kind: ResultEvent['kind'] = 'RESULT'
): ResultEvent => ({
  kind,
  nodeId,
  sequence,
  payload: { n: sequence },
  status: null,
  truncated: false,
  timestamp: '2026-07-26T14:22:11Z',
});

let events: ResultEvent[] = [];
let truncated = false;
let fail = false;

const server = setupServer(
  http.get('/api/sessions/:sid/executions/:eid/results', ({ request }) =>
    fail
      ? new HttpResponse(null, { status: 500 })
      : HttpResponse.json({
          executionId: 'e1',
          complete: true,
          status: 'SUCCESS',
          truncated,
          nextCursor: events.length,
          events,
          cursorSeen: new URL(request.url).searchParams.get('afterCursor'),
        })
  )
);

let blobs: Blob[] = [];
let names: string[] = [];

beforeAll(() => server.listen());
beforeEach(() => {
  installMockEventSource();
  blobs = [];
  names = [];
  URL.createObjectURL = vi.fn((blob: Blob) => {
    blobs.push(blob);
    return 'blob:x';
  });
  URL.revokeObjectURL = vi.fn();
  vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (
    this: HTMLAnchorElement
  ) {
    names.push(this.download);
  });
});
afterEach(() => {
  events = [];
  truncated = false;
  fail = false;
  vi.restoreAllMocks();
});
afterAll(() => server.close());

const explorer = () =>
  renderWithProviders(
    <ResultsExplorer
      graph={createMockGraph()}
      sessionId="s1"
      executionId="e1"
    />
  );

describe('ResultsExplorer', () => {
  it('pages the results, 20 at a time', async () => {
    events = Array.from({ length: 25 }, (_, i) => event(i, 'sink-storage'));
    const user = userEvent.setup();
    explorer();

    expect(await screen.findAllByText(/sink-storage · #/)).toHaveLength(20);
    await user.click(
      screen.getByRole('button', { name: 'Load more (5 left)' })
    );
    expect(screen.getAllByText(/sink-storage · #/)).toHaveLength(25);
    expect(
      screen.queryByRole('button', { name: /Load more/ })
    ).not.toBeInTheDocument();
  });

  it('filters by terminal node and shows only RESULT events', async () => {
    events = [
      event(0, 'sink-storage'),
      event(1, 'other'),
      event(2, 'sink-storage', 'NODE_END'),
    ];
    const user = userEvent.setup();
    explorer();
    await screen.findByText('sink-storage · #0');

    expect(screen.getByText('other · #1')).toBeInTheDocument();
    expect(screen.queryByText('sink-storage · #2')).not.toBeInTheDocument();

    const filter = screen.getByLabelText('Filter by node');
    expect(
      within(filter)
        .getAllByRole('option')
        .map((o) => o.textContent)
    ).toEqual(['All terminal nodes', 'sink-storage']);
    await user.selectOptions(filter, 'sink-storage');
    expect(screen.queryByText('other · #1')).not.toBeInTheDocument();
    expect(screen.getByText('sink-storage · #0')).toBeInTheDocument();
  });

  it('downloads the matching results as one JSON array', async () => {
    events = [event(0, 'sink-storage'), event(1, 'sink-storage')];
    const user = userEvent.setup();
    explorer();
    await screen.findByText('sink-storage · #0');

    await user.click(screen.getByRole('button', { name: 'Download JSON' }));

    expect(names).toEqual(['results-e1.json']);
    const saved = JSON.parse(await blobs[0].text()) as {
      sequence: number;
      payload: unknown;
    }[];
    expect(saved.map((r) => r.sequence)).toEqual([0, 1]);
    expect(saved[0].payload).toEqual({ n: 0 });
  });

  it('says so when there are no results, for a node or at all, and when some were cut', async () => {
    truncated = true;
    const user = userEvent.setup();
    explorer();

    expect(await screen.findByText('No results.')).toBeInTheDocument();
    expect(screen.getByText(/too large/)).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: 'Download JSON' })
    ).toBeDisabled();
    await user.selectOptions(
      screen.getByLabelText('Filter by node'),
      'sink-storage'
    );
    expect(
      screen.getByText('No results from sink-storage.')
    ).toBeInTheDocument();
  });

  it('reports a failure and retries', async () => {
    fail = true;
    const user = userEvent.setup();
    explorer();
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Failed to load the results'
    );

    fail = false;
    events = [event(0, 'sink-storage')];
    await user.click(screen.getByRole('button', { name: 'Retry' }));
    expect(await screen.findByText('sink-storage · #0')).toBeInTheDocument();
  });

  it('opens from the Result tab once the run has finished', async () => {
    events = [event(0, 'sink-storage')];
    const user = userEvent.setup();
    const panel = (status: string) => (
      <ProgressPanel
        graph={createMockGraph()}
        progress={createMockProgress({ executionId: 'e1', status })}
        tasksByNode={{}}
        sessionId="s1"
        onClose={vi.fn()}
      />
    );
    const running = renderWithProviders(panel('RUNNING'));
    await user.click(screen.getByRole('tab', { name: 'Result' }));
    expect(screen.getByText(/explored once the run ends/)).toBeInTheDocument();
    running.unmount();

    renderWithProviders(panel('SUCCESS'));
    await user.click(screen.getByRole('tab', { name: 'Result' }));
    expect(await screen.findByText('sink-storage · #0')).toBeInTheDocument();
    expect(screen.queryByTestId('task-sequence')).not.toBeInTheDocument();

    await user.click(screen.getByRole('tab', { name: 'Progress' }));
    expect(screen.getByTestId('task-sequence')).toBeInTheDocument();
  });
});
