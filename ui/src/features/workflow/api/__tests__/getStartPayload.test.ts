// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { getStartPayload } from '../getStartPayload';

const server = setupServer(
  http.get('/api/executions/:executionId/start-payload', ({ params }) =>
    HttpResponse.json({
      timestamp: '2026-11-19T00:00:00Z',
      status: 200,
      message: 'Start payload retrieved',
      data: { payload: { run: params.executionId } },
      path: `/api/executions/${String(params.executionId)}/start-payload`,
    })
  )
);

beforeAll(() => server.listen());
afterAll(() => server.close());

describe('getStartPayload', () => {
  it('fetches what the run was started with', async () => {
    expect(await getStartPayload('e 1')).toEqual({
      payload: { run: 'e 1' },
    });
  });
});
