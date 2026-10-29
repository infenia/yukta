// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { MemoryRouter } from 'react-router';
import { QueryClientProvider } from '@tanstack/react-query';
import { createTestQueryClient } from '@/test/utils/testUtils';
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

function renderRuns(sessionId = 'dogfood') {
  return render(
    <QueryClientProvider client={createTestQueryClient()}>
      <MemoryRouter>
        <RecentRuns sessionId={sessionId} />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

describe('RecentRuns', () => {
  it("lists the session's newest runs, linking each to its run view", async () => {
    runs = [
      {
        sessionId: 'dogfood',
        executionId: '87bff7ec-1111-4000-8000-000000000000',
        workflowId: 'tool',
        status: 'SUCCESS',
        startTime: '2026-10-29T22:36:56',
        endTime: '2026-10-29T22:38:16',
      },
    ];
    renderRuns();

    const run = await screen.findByRole('link', { name: '87bff7ec' });
    expect(run).toHaveAttribute(
      'href',
      '/sessions/dogfood/workflow/tool?exec=87bff7ec-1111-4000-8000-000000000000'
    );
    expect(screen.getByText('Succeeded')).toBeInTheDocument();
    expect(screen.getByText('1m 20s')).toBeInTheDocument();
    expect(requests[0].get('sessionId')).toBe('dogfood');
    expect(requests[0].get('limit')).toBe('5');
  });

  it('links to all runs of the session and says when there are none', async () => {
    renderRuns('my session');
    expect(await screen.findByText(/No runs yet/)).toBeInTheDocument();
    expect(
      screen.getByRole('link', { name: 'All runs of my session' })
    ).toHaveAttribute('href', '/runs?session=my%20session');
  });
});
