// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { getSessionSummaries } from '../getSessionSummaries';
import { createMockSessions } from '@/test/factories/sessionFactory';

const mockSessionData = createMockSessions(2);

const server = setupServer(
  http.get('/api/sessions/summaries', () => {
    return HttpResponse.json({
      timestamp: new Date().toISOString(),
      status: 200,
      message: 'Session summaries retrieved successfully',
      data: mockSessionData,
      path: '/api/sessions/summaries',
    });
  })
);

beforeAll(() => server.listen());
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

describe('getSessionSummaries', () => {
  it('should fetch and return valid session summaries', async () => {
    const result = await getSessionSummaries();

    expect(result).toHaveLength(2);
    expect(result[0]).toMatchObject({
      sessionId: expect.any(String),
      name: expect.any(String),
      description: expect.any(String),
      initiator: expect.any(String),
      tags: expect.any(Array),
      projectPath: expect.any(String),
      workflowCount: expect.any(Number),
    });
  });

  it('should handle empty tags array', async () => {
    server.use(
      http.get('/api/sessions/summaries', () => {
        return HttpResponse.json({
          timestamp: new Date().toISOString(),
          status: 200,
          message: 'Session summaries retrieved successfully',
          data: createMockSessions(1, { tags: [] }),
          path: '/api/sessions/summaries',
        });
      })
    );

    const result = await getSessionSummaries();
    expect(result[0].tags).toEqual([]);
  });

  it('should return multiple sessions', async () => {
    const result = await getSessionSummaries();
    expect(result.length).toBeGreaterThanOrEqual(1);
  });

  it('follows the cursor, so a long list is not cut off', async () => {
    const cursors: (string | null)[] = [];
    server.use(
      http.get('/api/sessions/summaries', ({ request }) => {
        const url = new URL(request.url);
        const cursor = url.searchParams.get('cursor');
        cursors.push(cursor);
        return HttpResponse.json(
          {
            timestamp: new Date().toISOString(),
            status: 200,
            message: 'ok',
            data: cursor ? createMockSessions(1) : createMockSessions(2),
            path: '/api/sessions/summaries',
          },
          cursor ? undefined : { headers: { 'X-Next-Cursor': 'session-2' } }
        );
      })
    );

    const result = await getSessionSummaries();

    expect(result).toHaveLength(3);
    expect(cursors).toEqual([null, 'session-2']);
  });
});
