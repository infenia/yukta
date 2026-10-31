// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { useSessionDetails } from '../useSessionDetails';
import { createTestWrapper } from '@/test/utils/testUtils';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';

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
        projectPath: '/path',
        workflowIds: ['wf1'],
      },
    });
  })
);

describe('useSessionDetails', () => {
  beforeAll(() => {
    server.listen();
  });

  afterAll(() => {
    server.close();
  });


  it('fetches session details when sessionId is provided', async () => {
    const { result } = renderHook(() => useSessionDetails('sess-123'), {
      wrapper: createTestWrapper(),
    });

    await waitFor(() => {
      expect(result.current.isLoading).toBe(false);
    });

    expect(result.current.data?.name).toBe('Test Session');
  });

  it('does not fetch when sessionId is empty', () => {
    const { result } = renderHook(() => useSessionDetails(''), {
      wrapper: createTestWrapper(),
    });

    expect(result.current.isLoading).toBe(false);
  });
});
