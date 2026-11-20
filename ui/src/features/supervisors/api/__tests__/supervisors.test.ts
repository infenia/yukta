// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { getRunningSupervisors } from '../supervisors';

const running = [
  { id: 'guard', kind: 'SUPERVISOR' },
  { id: 'bus', kind: 'CONTROL_PLANE' },
];

const server = setupServer(
  http.get('/api/supervisors/status', () =>
    HttpResponse.json(
      { status: 200, message: 'ok', data: running },
      { status: 200 }
    )
  )
);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

describe('getRunningSupervisors', () => {
  it('returns the running supervisors and control planes reported by the control bus', async () => {
    const result = await getRunningSupervisors();
    expect(result).toEqual(running);
  });
});
