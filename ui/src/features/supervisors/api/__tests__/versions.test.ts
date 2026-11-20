// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { getSupervisorVersions } from '../supervisors';

const envelope = (data: unknown, status = 200) =>
  HttpResponse.json({ status, message: 'ok', data }, { status });

const versions = [
  {
    version: 2,
    savedAt: '2026-11-19T10:00:00Z',
    supervisor: { id: 'guard', scope: 'server' },
  },
  {
    version: 1,
    savedAt: '2026-11-18T10:00:00Z',
    supervisor: { id: 'guard', scope: 'server' },
  },
];

let requestedId: string | null = null;

const server = setupServer(
  http.get('/api/supervisors/:id/versions', ({ params }) => {
    requestedId = String(params.id);
    return envelope(versions);
  })
);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  requestedId = null;
});
afterAll(() => server.close());

describe('getSupervisorVersions', () => {
  it('fetches the versions of one supervisor by its id', async () => {
    const result = await getSupervisorVersions('guard');
    expect(requestedId).toBe('guard');
    expect(result.map((v) => v.version)).toEqual([2, 1]);
    expect(result[0].supervisor).toEqual({ id: 'guard', scope: 'server' });
  });
});
