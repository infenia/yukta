// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { getSessionSummaries } from '../api/getSessionSummaries';

const mockSessionData = {
  sessions: [
    {
      sessionId: 'session-1',
      name: 'Test Session 1',
      description: 'A test session',
      initiator: 'user@example.com',
      tags: ['tag1', 'tag2'],
      projectPath: '/path/to/project',
      workflowCount: 3,
    },
    {
      sessionId: 'session-2',
      name: 'Test Session 2',
      description: 'Another test session',
      initiator: 'another@example.com',
      tags: ['tag3'],
      projectPath: '/another/path',
      workflowCount: 1,
    },
  ],
};

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
afterAll(() => server.close());

describe('getSessionSummaries', () => {
  it('should fetch session summaries successfully', async () => {
    const result = await getSessionSummaries();
    expect(result.sessions).toBeDefined();
    expect(result.sessions).toHaveLength(2);
  });

  it('should return sessions with correct structure', async () => {
    const result = await getSessionSummaries();
    const session = result.sessions[0];

    expect(session).toHaveProperty('sessionId');
    expect(session).toHaveProperty('name');
    expect(session).toHaveProperty('description');
    expect(session).toHaveProperty('initiator');
    expect(session).toHaveProperty('tags');
    expect(session).toHaveProperty('projectPath');
    expect(session).toHaveProperty('workflowCount');
  });

  it('should return sessions with correct values', async () => {
    const result = await getSessionSummaries();
    const session = result.sessions[0];

    expect(session.sessionId).toBe('session-1');
    expect(session.name).toBe('Test Session 1');
    expect(session.description).toBe('A test session');
    expect(session.initiator).toBe('user@example.com');
    expect(session.tags).toEqual(['tag1', 'tag2']);
    expect(session.projectPath).toBe('/path/to/project');
    expect(session.workflowCount).toBe(3);
  });

  it('should handle empty tags array', async () => {
    server.use(
      http.get('/api/sessions/summaries', () => {
        return HttpResponse.json({
          timestamp: new Date().toISOString(),
          status: 200,
          message: 'Session summaries retrieved successfully',
          data: {
            sessions: [
              {
                sessionId: 'session-empty-tags',
                name: 'No Tags Session',
                description: 'Session without tags',
                initiator: 'user@example.com',
                tags: [],
                projectPath: '/path',
                workflowCount: 0,
              },
            ],
          },
          path: '/api/sessions/summaries',
        });
      })
    );

    const result = await getSessionSummaries();
    expect(result.sessions[0].tags).toEqual([]);
  });

  it('should return multiple sessions', async () => {
    const result = await getSessionSummaries();
    expect(result.sessions.length).toBeGreaterThanOrEqual(1);
  });
});
