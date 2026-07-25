// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { getSessionDetails } from '../getSessionDetails';

const server = setupServer(
  http.get('/api/sessions/:sessionId', () => {
    return HttpResponse.json({
      timestamp: '2026-07-25T00:00:00Z',
      status: 200,
      message: 'Session details retrieved',
      data: {
        sessionId: 'sess-123',
        name: 'Test Session',
        description: 'A test session',
        initiator: 'test-user',
        tags: ['tag1'],
        projectPath: '/path/to/project',
        workflowIds: ['wf1'],
      },
    });
  })
);

beforeAll(() => server.listen());
afterAll(() => server.close());

describe('getSessionDetails', () => {
  it('fetches session details successfully', async () => {
    const result = await getSessionDetails('sess-123');
    expect(result.sessionId).toBe('sess-123');
    expect(result.name).toBe('Test Session');
    expect(result.tags).toHaveLength(1);
  });
});
