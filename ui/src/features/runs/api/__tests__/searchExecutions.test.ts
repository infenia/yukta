// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { searchExecutions, type RunHit } from '../searchExecutions';
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

// one request per status: each returns runs, some with no start time
let byStatus: Record<string, RunHit[]> = {};

const server = setupServer(
  http.get('/api/executions', ({ request }) => {
    const status = new URL(request.url).searchParams.get('status') ?? '';
    return HttpResponse.json({
      timestamp: 't',
      status: 200,
      message: 'ok',
      data: byStatus[status] ?? [],
      path: '/api/executions',
    });
  })
);

beforeAll(() => server.listen());
afterEach(() => {
  server.resetHandlers();
  byStatus = {};
});
afterAll(() => server.close());

describe('searching runs', () => {
  it('keeps the newest runs of several statuses and puts runs without a start time last', async () => {
    byStatus = {
      running: [hit('a', null), hit('b', '2026-11-20T10:00:00Z')],
      failed: [hit('c', '2026-11-21T10:00:00Z'), hit('d', null)],
    };
    const runs = await searchExecutions(filters('running,failed'), 10, 0);
    expect(runs.map((r) => r.executionId)).toEqual(['c', 'b', 'a', 'd']);
  });
});
