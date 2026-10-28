// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeAll, afterAll, afterEach, vi } from 'vitest';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import { render } from '@testing-library/react';
import { QueryClientProvider } from '@tanstack/react-query';
import { createTestQueryClient } from '@/test/utils/testUtils';
import { createMockSession } from '@/test/factories/sessionFactory';
import { HistoryRedirect } from '@/routes/router';
import { RunsPage } from '../RunsPage';
import { parseFilters, timeBounds, toParams } from '../../lib/filters';
import type { RunHit } from '../../api/searchExecutions';

const ok = (data: unknown) =>
  HttpResponse.json({ timestamp: 't', status: 200, message: 'ok', data, path: '/' });

const hit = (n: number, overrides: Partial<RunHit> = {}): RunHit => ({
  sessionId: 's1',
  executionId: `${String(n).padStart(4, '0')}xyz-abcdef`,
  workflowId: 'wf',
  status: 'SUCCESS',
  startTime: '2026-07-26T14:22:01',
  endTime: '2026-07-26T14:22:10',
  ...overrides,
});

const requests: URLSearchParams[] = [];
let total = 0;
let fail = false;

const server = setupServer(
  http.get('/api/sessions/summaries', () => ok([createMockSession({ sessionId: 's1', name: 'One' }), createMockSession({ sessionId: 's2', name: '' })])),
  http.get('/api/sessions/s1/workflows', () => ok([{ workflowId: 'wf' }])),
  http.get('/api/executions', ({ request }) => {
    const params = new URL(request.url).searchParams;
    requests.push(params);
    if (fail) return HttpResponse.json({ message: 'down' }, { status: 500 });
    const limit = Number(params.get('limit'));
    return ok(Array.from({ length: Math.min(limit, total) }, (_, i) => hit(i)));
  })
);

beforeAll(() => server.listen());
afterEach(() => {
  requests.length = 0;
  total = 0;
  fail = false;
});
afterAll(() => server.close());

function Where() {
  const location = useLocation();
  return <output data-testid="where">{location.pathname + location.search}</output>;
}
function renderAt(url: string) {
  return render(
    <QueryClientProvider client={createTestQueryClient()}>
      <MemoryRouter initialEntries={[url]}>
        <Where />
        <Routes>
          <Route path="/runs" element={<RunsPage />} />
          <Route path="/history" element={<HistoryRedirect />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  );
}

describe('filters', () => {
  it('round-trips through the URL, leaving empty fields out', () => {
    const filters = parseFilters(
      new URLSearchParams('session=s1&workflow=wf&status=FAILURE&text=ab&range=custom&since=2026-01-01T00:00&until=2026-01-02T00:00')
    );
    expect(parseFilters(toParams(filters))).toEqual(filters);
    expect(toParams(parseFilters(new URLSearchParams('range=bogus&status=')))).toEqual(new URLSearchParams());
  });

  it('turns a preset into a local since, and passes a custom range as typed', () => {
    const now = new Date(2026, 6, 26, 15, 0, 0).getTime();
    const base = parseFilters(new URLSearchParams());
    expect(timeBounds({ ...base, range: '1h' }, now)).toEqual({ since: '2026-07-26T14:00:00' });
    expect(timeBounds({ ...base, range: '7d' }, now)).toEqual({ since: '2026-07-19T15:00:00' });
    expect(timeBounds(base, now)).toEqual({});
    expect(timeBounds({ ...base, range: 'custom', since: 'a', until: 'b' }, now)).toEqual({ since: 'a', until: 'b' });
    expect(timeBounds({ ...base, range: 'custom' }, now)).toEqual({});
  });
});

describe('RunsPage', () => {
  it('lists runs of all sessions and opens a run on its workflow page', async () => {
    total = 2;
    renderAt('/runs');
    const link = await screen.findByRole('link', { name: '0000xyz-' });
    expect(link).toHaveAttribute('href', '/sessions/s1/workflow/wf?exec=0000xyz-abcdef');
    expect(requests[0].get('limit')).toBe('100');
    expect(screen.getAllByText('9s')).toHaveLength(2);
  });

  it('copies a run id', async () => {
    total = 1;
    const user = userEvent.setup();
    const write = vi.spyOn(navigator.clipboard, 'writeText');
    renderAt('/runs');
    await user.click(await screen.findByRole('button', { name: 'Copy run id 0000xyz-abcdef' }));
    expect(write).toHaveBeenCalledWith('0000xyz-abcdef');
  });

  it('puts the filters in the URL and sends them to the search', async () => {
    total = 1;
    const user = userEvent.setup();
    renderAt('/runs');
    await screen.findByRole('link', { name: '0000xyz-' });
    expect(screen.getByLabelText('Workflow')).toBeDisabled();

    await user.selectOptions(screen.getByLabelText('Session'), 's1');
    await user.selectOptions(await screen.findByRole('combobox', { name: 'Workflow' }), 'wf');
    await user.selectOptions(screen.getByLabelText('Status'), 'FAILURE');
    await user.type(screen.getByLabelText('Run id contains'), 'ab');
    await user.selectOptions(screen.getByLabelText('Time range'), '1h');

    await waitFor(() => {
      const last = requests.at(-1) as URLSearchParams;
      expect(last.get('sessionId')).toBe('s1');
      expect(last.get('workflowId')).toBe('wf');
      expect(last.get('status')).toBe('FAILURE');
      expect(last.get('text')).toBe('ab');
      expect(last.get('since')).toMatch(/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d$/);
    });
    expect(screen.getByTestId('where')).toHaveTextContent(
      '/runs?session=s1&workflow=wf&status=FAILURE&text=ab&range=1h'
    );
  });

  it('reads the filters from the URL, with custom bounds', async () => {
    total = 1;
    const user = userEvent.setup();
    renderAt('/runs?range=custom&since=2026-07-01T00:00');
    await screen.findByRole('link', { name: '0000xyz-' });
    expect(screen.getByLabelText('Since')).toHaveValue('2026-07-01T00:00');
    fireEvent.change(screen.getByLabelText('Since'), { target: { value: '2026-07-01T06:00' } });
    await waitFor(() => expect(requests.at(-1)?.get('since')).toBe('2026-07-01T06:00'));
    expect(screen.getByRole('option', { name: 's2' })).toBeInTheDocument();
    await user.type(screen.getByLabelText('Until'), '2026-07-02T00:00');
    await waitFor(() => expect(requests.at(-1)?.get('until')).toBe('2026-07-02T00:00'));
    expect(requests.at(-1)?.get('since')).toBe('2026-07-01T06:00');
  });

  it('loads more until the server has no more, and resets on a filter change', async () => {
    total = 150;
    const user = userEvent.setup();
    renderAt('/runs');
    await screen.findAllByRole('link', { name: /xyz-/ });
    expect(screen.getAllByRole('link', { name: /xyz-/ })).toHaveLength(100);

    await user.click(screen.getByRole('button', { name: 'Load more' }));
    await waitFor(() => expect(screen.getAllByRole('link', { name: /xyz-/ })).toHaveLength(150));
    expect(requests.at(-1)?.get('limit')).toBe('200');
    expect(screen.queryByRole('button', { name: 'Load more' })).not.toBeInTheDocument();

    await user.selectOptions(screen.getByLabelText('Status'), 'ERROR');
    await waitFor(() => expect(requests.at(-1)?.get('limit')).toBe('100'));
  });

  it('stops offering more at the server maximum', async () => {
    total = 5000;
    const user = userEvent.setup();
    renderAt('/runs');
    for (let n = 100; n < 1000; n += 100) {
      await user.click(await screen.findByRole('button', { name: 'Load more' }));
      await waitFor(() => expect(requests.at(-1)?.get('limit')).toBe(String(n + 100)));
    }
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Load more' })).not.toBeInTheDocument());
  }, 60_000);

  it('says so when nothing matches, and when the search fails', async () => {
    renderAt('/runs');
    expect(await screen.findByText('No runs found.')).toBeInTheDocument();
  });

  it('reports a failed search and retries', async () => {
    fail = true;
    const user = userEvent.setup();
    renderAt('/runs');
    expect(await screen.findByRole('alert')).toHaveTextContent('Failed to load runs');
    fail = false;
    total = 1;
    await user.click(screen.getByRole('button', { name: 'Retry' }));
    expect(await screen.findByRole('link', { name: '0000xyz-' })).toBeInTheDocument();
  });

  it('redirects /history to /runs and keeps its query', async () => {
    total = 1;
    renderAt('/history?session=s1');
    await screen.findByRole('link', { name: '0000xyz-' });
    expect(screen.getByTestId('where')).toHaveTextContent('/runs?session=s1');
    expect(requests[0].get('sessionId')).toBe('s1');
  });
});
