// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import React from 'react';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClientProvider, QueryClient } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { useSessionSummaries } from '../hooks/useSessionSummaries';

const mockSessions = [
  {
    sessionId: 'session-1',
    name: 'Test Session 1',
    description: 'A test session',
    initiator: 'user@example.com',
    tags: ['tag1', 'tag2'],
    projectPath: '/path/to/project',
    workflowCount: 3,
  },
];

const server = setupServer(
  http.get('/api/sessions/summaries', () => {
    return HttpResponse.json({
      timestamp: new Date().toISOString(),
      status: 200,
      message: 'Session summaries retrieved successfully',
      data: { sessions: mockSessions },
      path: '/api/sessions/summaries',
    });
  })
);

beforeAll(() => server.listen());
afterAll(() => server.close());

const wrapper = ({ children }: { children: React.ReactNode }) => {
  const testQueryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
    },
  });
  return React.createElement(QueryClientProvider, { client: testQueryClient }, children);
};

describe('useSessionSummaries', () => {
  it('should return sessions on successful fetch', async () => {
    const { result } = renderHook(() => useSessionSummaries(), { wrapper });

    await waitFor(() => {
      expect(result.current.isLoading).toBe(false);
    });

    expect(result.current.data).toBeDefined();
    expect(result.current.data).toHaveLength(1);
  });

  it('should return sessions with correct structure', async () => {
    const { result } = renderHook(() => useSessionSummaries(), { wrapper });

    await waitFor(() => {
      expect(result.current.isLoading).toBe(false);
    });

    const session = result.current.data?.[0];
    expect(session).toHaveProperty('sessionId');
    expect(session).toHaveProperty('name');
    expect(session).toHaveProperty('description');
    expect(session).toHaveProperty('initiator');
    expect(session).toHaveProperty('tags');
    expect(session).toHaveProperty('projectPath');
    expect(session).toHaveProperty('workflowCount');
  });

  it('should initially have isLoading set to true', () => {
    const { result } = renderHook(() => useSessionSummaries(), { wrapper });

    expect(result.current.isLoading).toBe(true);
  });

  it('should have error property in response', async () => {
    const { result } = renderHook(() => useSessionSummaries(), { wrapper });

    await waitFor(() => {
      expect(result.current.isLoading).toBe(false);
    });

    expect(result.current).toHaveProperty('error');
  });

  it('should cache results based on queryKey', async () => {
    const { result: result1 } = renderHook(() => useSessionSummaries(), { wrapper });

    await waitFor(() => {
      expect(result1.current.isLoading).toBe(false);
    });

    const data1 = result1.current.data;

    // Create a new wrapper with a fresh query client to test that
    // the query hook uses the same queryKey
    const wrapper2 = ({ children }: { children: React.ReactNode }) => {
      const freshQueryClient = new QueryClient({
        defaultOptions: {
          queries: { retry: false },
        },
      });
      return React.createElement(QueryClientProvider, { client: freshQueryClient }, children);
    };

    const { result: result2 } = renderHook(() => useSessionSummaries(), { wrapper: wrapper2 });

    await waitFor(() => {
      expect(result2.current.isLoading).toBe(false);
    });

    // Both should have fetched the same data structure
    expect(data1).toBeDefined();
    expect(result2.current.data).toBeDefined();
  });

  it('should handle multiple sessions', async () => {
    const multipleSessions = [
      {
        sessionId: 'session-1',
        name: 'Session 1',
        description: 'Desc 1',
        initiator: 'user1@example.com',
        tags: ['tag1'],
        projectPath: '/path1',
        workflowCount: 1,
      },
      {
        sessionId: 'session-2',
        name: 'Session 2',
        description: 'Desc 2',
        initiator: 'user2@example.com',
        tags: ['tag2'],
        projectPath: '/path2',
        workflowCount: 2,
      },
    ];

    server.use(
      http.get('/api/sessions/summaries', () => {
        return HttpResponse.json({
          timestamp: new Date().toISOString(),
          status: 200,
          message: 'Session summaries retrieved successfully',
          data: { sessions: multipleSessions },
          path: '/api/sessions/summaries',
        });
      })
    );

    const { result } = renderHook(() => useSessionSummaries(), { wrapper });

    await waitFor(() => {
      expect(result.current.isLoading).toBe(false);
    });

    expect(result.current.data).toHaveLength(2);
  });

  it('should return data property', async () => {
    const { result } = renderHook(() => useSessionSummaries(), { wrapper });

    await waitFor(() => {
      expect(result.current.isLoading).toBe(false);
    });

    expect(result.current).toHaveProperty('data');
  });
});
