// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { getSupervisorDecisions, setAttachmentEnabled } from '../supervisors';

const envelope = (data: unknown, status = 200) =>
  HttpResponse.json({ status, message: 'ok', data }, { status });

const view = (id: string, scope: string, extra: object = {}) => ({
  id,
  supervisorId: 'guard',
  scope,
  enabled: true,
  source: 'MANUAL',
  originSessionId: null,
  originWorkflowId: null,
  ...extra,
});

let puts: unknown[] = [];
let stored: unknown[] = [];

const server = setupServer(
  http.get('/api/supervisors/assignments', () => envelope(stored)),
  http.get('/api/supervisors/:id/assignments', () => envelope(stored)),
  http.put('/api/supervisors/:id/assignments', async ({ request }) => {
    const body = (await request.json()) as { target: string }[];
    puts.push(body);
    return envelope(body.map((b, i) => view(`asg-${i}`, b.target)));
  })
);

beforeAll(() => server.listen());
afterEach(() => {
  server.resetHandlers();
  puts = [];
  stored = [];
});
afterAll(() => server.close());

describe('assignments through the supervisor API', () => {
  it('enables one assignment by id, leaving the rest and keeping locks', async () => {
    stored = [
      view('a1', 'session:s1', { locked: true }),
      view('a2', 'server', { enabled: false }),
      view('a3', 'workflow:w', { source: 'DEFINITION' }),
    ];

    const saved = await setAttachmentEnabled('a2', true);

    expect(puts).toEqual([
      [
        { target: 'session:s1', enabled: true, locked: true },
        { target: 'server', enabled: true, locked: false },
      ],
    ]);
    expect(saved).toBeUndefined();
  });

  it('says when the assignment is not there', async () => {
    await expect(setAttachmentEnabled('gone', true)).rejects.toThrow(
      'Assignment gone not found'
    );
  });

  it('asks for the decisions of one supervisor', async () => {
    let asked = '';
    server.use(
      http.get('/api/supervisors/decisions', ({ request }) => {
        asked = new URL(request.url).search;
        return envelope([]);
      })
    );
    await getSupervisorDecisions('guard', 5);
    expect(asked).toBe('?limit=5&supervisor=guard');
  });
});
