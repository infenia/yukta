// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import React from 'react';
import type { ReactNode } from 'react';
import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { useSessionSummaries } from '../useSessionSummaries';
import { createMockSessions } from '@/test/factories/sessionFactory';
import { createTestQueryClient, createTestWrapper } from '@/test/utils/testUtils';

const mockSessions = createMockSessions(1);

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
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

describe('useSessionSummaries', () => {
  let queryClient: QueryClient;
  let wrapper: ({ children }: { children: ReactNode }) => React.ReactElement;

  beforeEach(() => {
    queryClient = createTestQueryClient();
    wrapper = createTestWrapper(queryClient);
  });

  it('should return loading state initially', () => {
    const { result } = renderHook(() => useSessionSummaries(), { wrapper });
    expect(result.current.isLoading).toBe(true);
  });

  it('should return sessions on successful fetch', async () => {
    const { result } = renderHook(() => useSessionSummaries(), { wrapper });

    await waitFor(() => {
      expect(result.current.isLoading).toBe(false);
    });

    expect(result.current.data).toHaveLength(1);
    expect(result.current).toHaveProperty('error');
  });

  it('should return sessions with correct structure', async () => {
    const { result } = renderHook(() => useSessionSummaries(), { wrapper });

    await waitFor(() => {
      expect(result.current.isLoading).toBe(false);
    });

    const session = result.current.data?.[0];
    expect(session).toMatchObject({
      sessionId: expect.any(String),
      name: expect.any(String),
      description: expect.any(String),
      initiator: expect.any(String),
      tags: expect.any(Array),
      projectPath: expect.any(String),
      workflowCount: expect.any(Number),
    });
  });

  it('should cache results on second call', async () => {
    const { result: result1 } = renderHook(() => useSessionSummaries(), { wrapper });

    await waitFor(() => {
      expect(result1.current.isLoading).toBe(false);
    });
    const data1 = result1.current.data;

    const { result: result2 } = renderHook(() => useSessionSummaries(), { wrapper });
    expect(result2.current.isLoading).toBe(false);
    expect(result2.current.data).toEqual(data1);
  });

  it('should handle multiple sessions', async () => {
    const multipleSessions = createMockSessions(2);

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
});
