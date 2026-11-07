// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { render, screen, fireEvent, within } from '@testing-library/react';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import { QueryClientProvider } from '@tanstack/react-query';
import { createTestQueryClient } from '@/test/utils/testUtils';
import { formatDateTime } from '@/features/workflow/lib/timeFormat';
import { RecentRuns } from '../RecentRuns';

const ok = (data: unknown) =>
  HttpResponse.json({
    timestamp: 't',
    status: 200,
    message: 'ok',
    data,
    path: '/',
  });

const requests: URLSearchParams[] = [];
let runs: unknown[] = [];

const server = setupServer(
  http.get('/api/executions', ({ request }) => {
    requests.push(new URL(request.url).searchParams);
    return ok(runs);
  })
);

beforeAll(() => server.listen());
afterEach(() => {
  requests.length = 0;
  runs = [];
});
afterAll(() => server.close());

const RUN_ID = '87bff7ec-1111-4000-8000-000000000000';
const RUN_VIEW = `/sessions/dogfood/workflow/tool?exec=${RUN_ID}`;

function LocationProbe() {
  const location = useLocation();
  return (
    <div data-testid="location">{location.pathname + location.search}</div>
  );
}

function renderRuns(sessionId = 'dogfood') {
  return render(
    <QueryClientProvider client={createTestQueryClient()}>
      <MemoryRouter>
        <Routes>
          <Route
            path="*"
            element={
              <>
                <RecentRuns sessionId={sessionId} />
                <LocationProbe />
              </>
            }
          />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  );
}

const oneRun = () => [
  {
    sessionId: 'dogfood',
    executionId: RUN_ID,
    workflowId: 'tool',
    status: 'SUCCESS',
    startTime: '2026-10-29T22:36:56',
    endTime: '2026-10-29T22:38:16',
  },
];

describe('RecentRuns', () => {
  it("lists the session's newest runs, linking each to its run view", async () => {
    runs = oneRun();
    renderRuns();

    const run = await screen.findByRole('link', { name: '87bff7ec' });
    expect(run).toHaveAttribute('href', RUN_VIEW);
    // Status is icon-only: the label lives in aria-label, not in the text.
    expect(screen.getByLabelText('Succeeded')).toBeInTheDocument();
    expect(screen.queryByText('Succeeded')).not.toBeInTheDocument();
    expect(screen.getByText('1m 20s')).toBeInTheDocument();
    expect(requests[0].get('sessionId')).toBe('dogfood');
    expect(requests[0].get('limit')).toBe('5');
  });

  it('shows the start date along with the time', async () => {
    runs = oneRun();
    renderRuns();

    const row = await screen.findByTestId(`run-row-${RUN_ID}`);
    expect(
      within(row).getByText(formatDateTime('2026-10-29T22:36:56'))
    ).toBeInTheDocument();
  });

  it('opens the run view when the row is clicked', async () => {
    runs = oneRun();
    renderRuns();

    fireEvent.click(await screen.findByTestId(`run-row-${RUN_ID}`));
    expect(screen.getByTestId('location')).toHaveTextContent(RUN_VIEW);
  });

  it('opens the run view once when the run link itself is clicked', async () => {
    runs = oneRun();
    renderRuns();

    fireEvent.click(await screen.findByRole('link', { name: '87bff7ec' }));
    expect(screen.getByTestId('location')).toHaveTextContent(RUN_VIEW);
  });

  it('opens the run view from the keyboard', async () => {
    runs = oneRun();
    renderRuns();

    fireEvent.keyDown(await screen.findByTestId(`run-row-${RUN_ID}`), {
      key: 'Enter',
    });
    expect(screen.getByTestId('location')).toHaveTextContent(RUN_VIEW);
  });

  it('opens the run view from the keyboard with Space', async () => {
    runs = oneRun();
    renderRuns();

    fireEvent.keyDown(await screen.findByTestId(`run-row-${RUN_ID}`), {
      key: ' ',
    });
    expect(screen.getByTestId('location')).toHaveTextContent(RUN_VIEW);
  });

  it('ignores other keys on a run row', async () => {
    runs = oneRun();
    renderRuns();

    fireEvent.keyDown(await screen.findByTestId(`run-row-${RUN_ID}`), {
      key: 'Tab',
    });
    expect(screen.getByTestId('location').textContent).toBe('/');
  });

  it('links to all runs of the session and says when there are none', async () => {
    renderRuns('my session');
    expect(await screen.findByText(/No runs yet/)).toBeInTheDocument();
    expect(
      screen.getByRole('link', { name: 'All runs of my session' })
    ).toHaveAttribute('href', '/runs?session=my%20session');
  });
});
