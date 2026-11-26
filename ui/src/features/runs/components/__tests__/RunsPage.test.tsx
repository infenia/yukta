// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { choose } from '@/test/utils/choose';
import {
  describe,
  it,
  expect,
  beforeAll,
  afterAll,
  afterEach,
  beforeEach,
  vi,
} from 'vitest';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import { render } from '@testing-library/react';
import { QueryClientProvider } from '@tanstack/react-query';
import { createTestQueryClient } from '@/test/utils/testUtils';
import {
  MockEventSource,
  installMockEventSource,
} from '@/test/mocks/MockEventSource';
import { createMockSession } from '@/test/factories/sessionFactory';
import { HistoryRedirect } from '@/routes/router';
import { RunsPage } from '../RunsPage';
import { parseFilters, timeBounds, toParams } from '../../lib/filters';
import type { RunHit } from '../../api/searchExecutions';

const withTotal = (data: unknown) =>
  HttpResponse.json(
    { timestamp: 't', status: 200, message: 'ok', data, path: '/' },
    { headers: { 'X-Total-Count': String(total) } }
  );

const ok = (data: unknown) =>
  HttpResponse.json({
    timestamp: 't',
    status: 200,
    message: 'ok',
    data,
    path: '/',
  });

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
/** The first run is ABORTED, so it offers Recover. */
let aborted = false;
const recovered: string[] = [];

const server = setupServer(
  http.get('/api/sessions/summaries', () =>
    ok([
      createMockSession({ sessionId: 's1', name: 'One' }),
      createMockSession({ sessionId: 's2', name: '' }),
    ])
  ),
  http.get('/api/sessions/s1/workflows', () => ok([{ workflowId: 'wf' }])),
  http.get('/api/executions', ({ request }) => {
    const params = new URL(request.url).searchParams;
    requests.push(params);
    if (fail) return HttpResponse.json({ message: 'down' }, { status: 500 });
    const limit = Number(params.get('limit'));
    const offset = Number(params.get('offset') ?? 0);
    return withTotal(
      Array.from(
        { length: Math.max(0, Math.min(limit, total - offset)) },
        (_, n) => {
          const i = offset + n;
          return hit(
            i,
            aborted && i === 0
              ? { status: 'ABORTED' }
              : i === 0
                ? { nodes: { done: 11, total: 100, failed: 'spotbugs' } }
                : {}
          );
        }
      )
    );
  }),
  http.get('/api/executions/counts', () =>
    ok({ running: 2, queued: 1, failed: 3 })
  ),
  http.post('/api/executions/:id/recover', ({ params }) => {
    recovered.push(params.id as string);
    return ok({ executionId: 'new-run' });
  })
);

beforeAll(() => server.listen());
beforeEach(() => installMockEventSource());
afterEach(() => {
  requests.length = 0;
  total = 0;
  fail = false;
  aborted = false;
  recovered.length = 0;
  server.resetHandlers();
});
afterAll(() => server.close());

function Where() {
  const location = useLocation();
  return (
    <output data-testid="where">{location.pathname + location.search}</output>
  );
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
      new URLSearchParams(
        'session=s1&workflow=wf&status=FAILURE&text=ab&range=custom&since=2026-01-01T00:00&until=2026-01-02T00:00'
      )
    );
    expect(parseFilters(toParams(filters))).toEqual(filters);
    expect(
      toParams(parseFilters(new URLSearchParams('range=bogus&status=')))
    ).toEqual(new URLSearchParams());
  });

  it('turns a preset into a UTC since, and passes an unparsable custom bound as typed', () => {
    const now = new Date(2026, 6, 26, 15, 0, 0).getTime();
    const base = parseFilters(new URLSearchParams());
    expect(timeBounds({ ...base, range: '1h' }, now)).toEqual({
      since: new Date(2026, 6, 26, 14, 0, 0).toISOString(),
    });
    expect(timeBounds({ ...base, range: '7d' }, now)).toEqual({
      since: new Date(2026, 6, 19, 15, 0, 0).toISOString(),
    });
    expect(timeBounds(base, now)).toEqual({});
    expect(
      timeBounds({ ...base, range: 'custom', since: 'a', until: 'b' }, now)
    ).toEqual({ since: 'a', until: 'b' });
    expect(
      timeBounds({ ...base, range: 'custom', since: '2026-07-01T06:00' }, now)
    ).toEqual({ since: new Date('2026-07-01T06:00').toISOString() });
    expect(timeBounds({ ...base, range: 'custom' }, now)).toEqual({});
  });
});

describe('RunsPage', () => {
  it('lists runs of all sessions and opens a run on its workflow page', async () => {
    total = 2;
    renderAt('/runs');
    const link = await screen.findByRole('link', { name: '0000xyz-' });
    expect(link).toHaveAttribute(
      'href',
      '/sessions/s1/workflow/wf?exec=0000xyz-abcdef'
    );
    expect(requests[0].get('limit')).toBe('25');
    expect(requests[0].get('offset')).toBe('0');
    expect(screen.getAllByText('9s')).toHaveLength(2);
    // the session by its name, then the workflow; and how many runs
    const cell = screen
      .getAllByRole('cell')
      .find((c) => c.dataset.label === 'Session / workflow');
    expect(cell).toHaveTextContent('One / wf');
    expect(screen.getByText(/1–2 of 2 runs/)).toBeInTheDocument();
  });

  it('names a session without a name by its ID, and counts one run', async () => {
    server.use(
      http.get('/api/executions', () => ok([hit(0, { sessionId: 's2' })]))
    );
    renderAt('/runs?range=24h');
    await screen.findByRole('link', { name: '0000xyz-' });
    expect(
      screen
        .getAllByRole('cell')
        .find((c) => c.dataset.label === 'Session / workflow')
    ).toHaveTextContent('s2 / wf');
    expect(
      screen.getByText('1–1 of 1 run in the last 24 hours')
    ).toBeInTheDocument();
  });

  it('recovers an aborted run after confirmation and opens the new run', async () => {
    total = 2;
    aborted = true;
    const user = userEvent.setup();
    renderAt('/runs');
    await user.click(await screen.findByRole('button', { name: /recover/i }));
    expect(screen.getAllByRole('button', { name: /recover/i })).toHaveLength(2);
    await user.click(
      within(screen.getByRole('dialog')).getByRole('button', {
        name: 'Recover',
      })
    );
    await waitFor(() =>
      expect(screen.getByTestId('where')).toHaveTextContent(
        '/sessions/s1/workflow/wf?exec=new-run'
      )
    );
    expect(recovered).toEqual(['0000xyz-abcdef']);
  });

  it('shows each run’s progress, and where it failed', async () => {
    total = 2;
    renderAt('/runs');
    await screen.findByRole('link', { name: '0000xyz-' });
    const cells = screen
      .getAllByRole('cell')
      .filter((c) => c.dataset.label === 'Progress');
    expect(cells[0]).toHaveTextContent('11/100failed at spotbugs');
    expect(cells[1]).toHaveTextContent('—');
    // a bar beside the count, red where it failed; none for a run without progress
    const bar = within(cells[0]).getByRole('progressbar');
    expect(bar).toHaveAttribute('aria-valuenow', '11');
    expect(bar).toHaveAttribute('aria-valuemax', '100');
    expect((bar.firstElementChild as HTMLElement).style.width).toBe('11%');
    expect(bar.firstElementChild).toHaveClass('bg-error');
    expect(within(cells[1]).queryByRole('progressbar')).toBeNull();
  });

  it('shows who started each run in an Initiator column, between Duration and Actions', async () => {
    server.use(
      http.get('/api/executions', () =>
        ok([
          hit(0, { initiator: 'yk check' }),
          hit(1),
          hit(2, { nodes: { done: 3, total: 0, failed: null } }),
          hit(3, { nodes: { done: 9, total: 4, failed: null } }),
        ])
      )
    );
    renderAt('/runs');
    await screen.findByRole('link', { name: '0000xyz-' });

    const names = screen
      .getAllByRole('columnheader')
      .map((header) => header.textContent);
    expect(names.indexOf('Initiator')).toBe(names.indexOf('Duration') + 1);
    expect(names.at(-1)).toBe('Actions');
    const cells = screen
      .getAllByRole('cell')
      .filter((c) => c.dataset.label === 'Initiator');
    expect(cells[0]).toHaveTextContent('yk check');
    // unknown: a dash
    expect(cells[1]).toHaveTextContent('—');

    // a run with no nodes counted has an empty bar; a count past the total is a full one
    const bars = screen.getAllByRole('progressbar');
    expect((bars[0].firstElementChild as HTMLElement).style.width).toBe('0%');
    expect((bars[1].firstElementChild as HTMLElement).style.width).toBe('100%');
  });

  it('says it updates live when the event stream is connected', async () => {
    total = 1;
    renderAt('/runs');
    await screen.findByRole('link', { name: '0000xyz-' });
    expect(screen.queryByTestId('runs-live')).toBeNull();

    MockEventSource.byUrl('/api/events/stream').open();
    expect(await screen.findByTestId('runs-live')).toHaveTextContent(
      'Updates live · filters are kept in the URL'
    );
  });

  it('refetches the runs when a run changes status', async () => {
    total = 1;
    renderAt('/runs');
    await screen.findByRole('link', { name: '0000xyz-' });
    const before = requests.length;

    const stream = MockEventSource.byUrl('/api/events/stream');
    stream.open();
    stream.emit({
      type: 'WorkflowStatusChanged',
      severity: 'INFO',
      rootExecutionId: 'r',
      depth: 0,
      event: {
        executionId: 'r',
        timestamp: 't',
        workflowId: 'wf',
        status: 'RUNNING',
      },
    });
    await waitFor(() => expect(requests.length).toBeGreaterThan(before));
  });

  it('counts runs that need attention, and filters by a count', async () => {
    total = 1;
    const user = userEvent.setup();
    renderAt('/runs');
    const failed = await screen.findByRole('button', {
      name: '3 failed in 24 h',
    });
    expect(screen.getByRole('button', { name: '2 running' })).toHaveAttribute(
      'aria-pressed',
      'false'
    );
    expect(
      screen.getByRole('button', { name: '1 queued' })
    ).toBeInTheDocument();

    await user.click(failed);
    expect(screen.getByTestId('where')).toHaveTextContent(
      '/runs?status=FAILURE&range=24h'
    );
    expect(failed).toHaveAttribute('aria-pressed', 'true');
    await user.click(failed);
    expect(screen.getByTestId('where')).toHaveTextContent(/^\/runs$/);
  });

  it('opens a run from a click anywhere on its row, but not from its buttons', async () => {
    total = 1;
    const user = userEvent.setup();
    renderAt('/runs');
    const row = (await screen.findByRole('link', { name: '0000xyz-' })).closest(
      'tr'
    ) as HTMLElement;
    await user.click(within(row).getByText('9s'));
    expect(screen.getByTestId('where')).toHaveTextContent(
      '/sessions/s1/workflow/wf?exec=0000xyz-abcdef'
    );
  });

  it('does not open the run when the copy button is clicked', async () => {
    total = 1;
    const user = userEvent.setup();
    vi.spyOn(navigator.clipboard, 'writeText').mockResolvedValue();
    renderAt('/runs');
    await user.click(
      await screen.findByRole('button', { name: 'Copy run id 0000xyz-abcdef' })
    );
    expect(screen.getByTestId('where')).toHaveTextContent(/^\/runs/);
  });

  it('opens a focused row with Enter', async () => {
    total = 1;
    const user = userEvent.setup();
    renderAt('/runs');
    const row = (await screen.findByRole('link', { name: '0000xyz-' })).closest(
      'tr'
    ) as HTMLElement;
    row.focus();
    await user.keyboard('{Enter}');
    expect(screen.getByTestId('where')).toHaveTextContent(
      '/sessions/s1/workflow/wf?exec=0000xyz-abcdef'
    );
  });

  it('opens a focused row with Space', async () => {
    total = 1;
    const user = userEvent.setup();
    renderAt('/runs');
    const row = (await screen.findByRole('link', { name: '0000xyz-' })).closest(
      'tr'
    ) as HTMLElement;
    row.focus();
    await user.keyboard(' ');
    expect(screen.getByTestId('where')).toHaveTextContent(
      '/sessions/s1/workflow/wf?exec=0000xyz-abcdef'
    );
  });

  it('ignores other keys on a focused row', async () => {
    total = 1;
    const user = userEvent.setup();
    renderAt('/runs');
    const row = (await screen.findByRole('link', { name: '0000xyz-' })).closest(
      'tr'
    ) as HTMLElement;
    row.focus();
    await user.keyboard('a');
    expect(screen.getByTestId('where')).toHaveTextContent(/^\/runs$/);
  });

  it('leaves Enter on a button inside the row to that button', async () => {
    total = 1;
    const user = userEvent.setup();
    const write = vi
      .spyOn(navigator.clipboard, 'writeText')
      .mockResolvedValue();
    renderAt('/runs');
    const copy = await screen.findByRole('button', {
      name: 'Copy run id 0000xyz-abcdef',
    });
    copy.focus();
    await user.keyboard('{Enter}');
    expect(write).toHaveBeenCalledWith('0000xyz-abcdef');
    expect(screen.getByTestId('where')).toHaveTextContent(/^\/runs/);
  });

  it('shows the status as an icon, its name kept as the accessible label', async () => {
    total = 1;
    renderAt('/runs');
    await screen.findByRole('link', { name: '0000xyz-' });
    const cell = screen
      .getAllByRole('cell')
      .find((c) => c.dataset.label === 'Status') as HTMLElement;
    expect(within(cell).getByTestId('status-chip')).toHaveAttribute(
      'aria-label',
      'Succeeded'
    );
    expect(within(cell).queryByText('Succeeded')).toBeNull();
  });

  it('copies a run id', async () => {
    total = 1;
    const user = userEvent.setup();
    const write = vi.spyOn(navigator.clipboard, 'writeText');
    renderAt('/runs');
    await user.click(
      await screen.findByRole('button', { name: 'Copy run id 0000xyz-abcdef' })
    );
    expect(write).toHaveBeenCalledWith('0000xyz-abcdef');
  });

  it('puts the filters in the URL and sends them to the search', async () => {
    total = 1;
    const user = userEvent.setup();
    renderAt('/runs');
    await screen.findByRole('link', { name: '0000xyz-' });
    expect(screen.getByLabelText('Workflow')).toBeDisabled();

    await choose(user, screen.getByLabelText('Session'), 's1');
    await choose(
      user,
      await screen.findByRole('combobox', { name: 'Workflow' }),
      'wf'
    );
    await choose(user, screen.getByLabelText('Status'), 'FAILURE');
    await user.type(screen.getByLabelText('Search runs'), 'ab');
    await choose(user, screen.getByLabelText('Time range'), '1h');

    await waitFor(() => {
      const last = requests.at(-1) as URLSearchParams;
      expect(last.get('sessionId')).toBe('s1');
      expect(last.get('workflowId')).toBe('wf');
      expect(last.get('status')).toBe('FAILURE');
      expect(last.get('text')).toBe('ab');
      expect(last.get('since')).toMatch(
        /^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}Z$/
      );
    });
    expect(screen.getByTestId('where')).toHaveTextContent(
      '/runs?session=s1&workflow=wf&status=FAILURE&text=ab&range=1h'
    );
  });

  it('reads the filters from the URL, with custom bounds', async () => {
    total = 1;
    const user = userEvent.setup();
    renderAt(
      '/runs?range=custom&since=2026-07-01T00:00&until=2026-07-02T00:00'
    );
    await screen.findByRole('link', { name: '0000xyz-' });
    expect(screen.getByLabelText('Since')).toHaveTextContent('Jul');
    expect(requests.at(-1)?.get('until')).toBe(
      new Date('2026-07-02T00:00').toISOString()
    );
    await user.click(screen.getByLabelText('Since'));
    fireEvent.change(await screen.findByLabelText('Hour'), {
      target: { value: '06' },
    });
    await waitFor(() =>
      expect(requests.at(-1)?.get('since')).toBe(
        new Date('2026-07-01T06:00').toISOString()
      )
    );
    await user.keyboard('{Escape}');
    await user.click(screen.getByLabelText('Session'));
    expect(
      await screen.findByRole('option', { name: 's2' })
    ).toBeInTheDocument();
    await user.keyboard('{Escape}');
    await user.click(screen.getByRole('button', { name: 'Clear Until' }));
    await waitFor(() => expect(requests.at(-1)?.get('until')).toBeNull());
    expect(requests.at(-1)?.get('since')).toBe(
      new Date('2026-07-01T06:00').toISOString()
    );
  });

  it('filters by several statuses at once, ticks them and can clear or select all', async () => {
    total = 1;
    const user = userEvent.setup();
    renderAt('/runs');
    await screen.findByRole('link', { name: '0000xyz-' });
    const trigger = screen.getByLabelText('Status');
    expect(trigger).toHaveTextContent('Any status');

    await user.click(trigger);
    await user.click(await screen.findByRole('option', { name: 'Running' }));
    // the popup stays open to pick the next one
    await user.click(screen.getByRole('option', { name: 'Failed' }));
    await waitFor(() => {
      expect(requests.at(-1)?.get('status')).toBe('RUNNING,FAILURE');
    });
    expect(screen.getByTestId('where')).toHaveTextContent(
      'status=RUNNING%2CFAILURE'
    );
    await waitFor(() => expect(trigger).toHaveTextContent('Running, Failed'));
    const ticked = screen
      .getAllByRole('option')
      .filter((o) => o.getAttribute('aria-selected') === 'true')
      .map((o) => o.textContent);
    expect(ticked).toEqual(['checkRunning', 'checkFailed']);

    await user.click(screen.getByRole('button', { name: 'Select all' }));
    await waitFor(() => expect(trigger).toHaveTextContent('9 selected'));
    expect(screen.getByRole('button', { name: 'Select all' })).toBeDisabled();

    await user.click(screen.getByRole('button', { name: 'Clear' }));
    await waitFor(() => expect(trigger).toHaveTextContent('Any status'));
    expect(screen.getByTestId('where')).toHaveTextContent('/runs');
    expect(screen.queryByRole('button', { name: 'Clear Status' })).toBeNull();
  });

  it('clears the statuses from the button on the trigger', async () => {
    total = 1;
    const user = userEvent.setup();
    renderAt('/runs?status=RUNNING');
    await screen.findByRole('link', { name: '0000xyz-' });
    await user.click(screen.getByRole('button', { name: 'Clear Status' }));
    await waitFor(() =>
      expect(screen.getByLabelText('Status')).toHaveTextContent('Any status')
    );
  });

  it('pages the runs, shows the total, and goes back to page 1 on a filter change', async () => {
    total = 60;
    const user = userEvent.setup();
    renderAt('/runs');
    // ByText: a role query over 25+ rows can time out under coverage
    expect(await screen.findAllByText(/xyz-/)).toHaveLength(25);
    expect(screen.getByText(/1–25 of 60 runs/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Previous' })).toBeDisabled();

    await user.click(screen.getByRole('button', { name: 'Next' }));
    await waitFor(() => expect(requests.at(-1)?.get('offset')).toBe('25'));
    await user.click(await screen.findByRole('button', { name: 'Next' }));
    await waitFor(() => expect(requests.at(-1)?.get('offset')).toBe('50'));
    expect(await screen.findByText(/51–60 of 60 runs/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled();

    await choose(user, screen.getByLabelText('Status'), 'ERROR');
    await waitFor(() => expect(requests.at(-1)?.get('offset')).toBe('0'));
  });

  it('changes the page size', async () => {
    total = 60;
    const user = userEvent.setup();
    renderAt('/runs');
    await screen.findAllByText(/xyz-/);
    await choose(user, screen.getByLabelText('Runs per page'), '50');
    await waitFor(() => expect(requests.at(-1)?.get('limit')).toBe('50'));
  });

  it('says so when nothing matches, and when the search fails', async () => {
    renderAt('/runs');
    expect(await screen.findByText('No runs found.')).toBeInTheDocument();
  });

  it('reports a failed search and retries', async () => {
    fail = true;
    const user = userEvent.setup();
    renderAt('/runs');
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Failed to load runs'
    );
    fail = false;
    total = 1;
    await user.click(screen.getByRole('button', { name: 'Retry' }));
    expect(
      await screen.findByRole('link', { name: '0000xyz-' })
    ).toBeInTheDocument();
  });

  it('redirects /history to /runs and keeps its query', async () => {
    total = 1;
    renderAt('/history?session=s1');
    await screen.findByRole('link', { name: '0000xyz-' });
    expect(screen.getByTestId('where')).toHaveTextContent('/runs?session=s1');
    expect(requests[0].get('sessionId')).toBe('s1');
  });
});
