// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import {
  searchExecutions,
  searchRunPage,
  type RunHit,
} from '../searchExecutions';
import type { RunFilters } from '../../lib/filters';

const hit = (executionId: string, startTime: string | null): RunHit => ({
  sessionId: 's1',
  executionId,
  workflowId: 'w1',
  status: 'x',
  startTime,
  endTime: null,
});

const filters = (status: string): RunFilters => ({
  session: '',
  workflow: '',
  status,
  text: '',
  range: '',
  since: '',
  until: '',
});

let seen: URLSearchParams | undefined;

const server = setupServer(
  http.get('/api/executions', ({ request }) => {
    seen = new URL(request.url).searchParams;
    return HttpResponse.json(
      {
        timestamp: 't',
        status: 200,
        message: 'ok',
        data: [hit('a', null)],
        path: '/api/executions',
      },
      { headers: { 'X-Total-Count': '57' } }
    );
  })
);

beforeAll(() => server.listen());
afterEach(() => {
  server.resetHandlers();
  seen = undefined;
});
afterAll(() => server.close());

describe('searching runs', () => {
  it('asks for a page with all statuses in one request and reads the total', async () => {
    const page = await searchRunPage(filters('running,failed'), 2, 25, 0);
    expect(seen?.get('status')).toBe('running,failed');
    expect(seen?.get('limit')).toBe('25');
    expect(seen?.get('offset')).toBe('50');
    expect(page.total).toBe(57);
    expect(page.runs.map((r) => r.executionId)).toEqual(['a']);
  });

  it('falls back to the page size when the server sends no total', async () => {
    server.use(
      http.get('/api/executions', () =>
        HttpResponse.json({ data: [hit('a', null)] })
      )
    );
    expect((await searchRunPage(filters(''), 0, 25)).total).toBe(1);
    expect((await searchExecutions(filters(''), 5)).length).toBe(1);
  });
});
