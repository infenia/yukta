// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import {
  attachSupervisor,
  detachAttachment,
  setAttachmentEnabled,
} from '../supervisors';

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
  it('adds a target to what the supervisor owns and keeps the locked ones locked', async () => {
    stored = [
      view('a1', 'session:s1', { locked: true }),
      view('d1', 'session:s2', { source: 'DEFINITION' }),
    ];

    const saved = await attachSupervisor({
      supervisorId: 'guard',
      scope: 'server',
      enabled: false,
    });

    expect(puts[0]).toEqual([
      { target: 'session:s1', enabled: true, locked: true },
      { target: 'server', enabled: false, locked: false },
    ]);
    expect(saved?.scope).toBe('server');
  });

  it('falls back to the first saved one when the server renames the target', async () => {
    server.use(
      http.put('/api/supervisors/:id/assignments', () =>
        envelope([view('x', 'session:other')])
      )
    );

    const saved = await attachSupervisor({
      supervisorId: 'guard',
      scope: 'server',
      enabled: true,
    });

    expect(saved?.id).toBe('x');
  });

  it('enables and removes one assignment by id, leaving the rest', async () => {
    stored = [
      view('a1', 'session:s1'),
      view('a2', 'server', { enabled: false }),
    ];

    await setAttachmentEnabled('a2', true);
    await detachAttachment('a1');

    expect(puts).toEqual([
      [
        { target: 'session:s1', enabled: true, locked: false },
        { target: 'server', enabled: true, locked: false },
      ],
      [{ target: 'server', enabled: false, locked: false }],
    ]);
  });

  it('says when the assignment is not there', async () => {
    await expect(detachAttachment('gone')).rejects.toThrow(
      'Assignment gone not found'
    );
  });
});
