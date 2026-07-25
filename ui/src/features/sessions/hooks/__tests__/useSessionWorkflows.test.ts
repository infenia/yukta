// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { useSessionWorkflows } from '../useSessionWorkflows';
import { createTestWrapper } from '@/test/utils/testUtils';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';

const server = setupServer(
  http.get('/api/sessions/:sessionId/workflows', () => {
    return HttpResponse.json({
      timestamp: '2026-07-25T00:00:00Z',
      status: 200,
      message: 'Workflow summaries retrieved',
      data: {
        workflows: [
          {
            workflowId: 'wf1',
            description: 'Workflow 1',
            nodeCount: 5,
            edgeCount: 4,
            status: 'SUCCESS',
          },
        ],
      },
    });
  })
);

describe('useSessionWorkflows', () => {
  beforeAll(() => {
    server.listen();
  });

  afterAll(() => {
    server.close();
  });


  it('fetches workflows when sessionId is provided', async () => {
    const { result } = renderHook(() => useSessionWorkflows('sess-123'), {
      wrapper: createTestWrapper(),
    });

    await waitFor(() => {
      expect(result.current.isLoading).toBe(false);
    });

    expect(result.current.data).toHaveLength(1);
    expect(result.current.data?.[0].workflowId).toBe('wf1');
  });

  it('does not fetch when sessionId is empty', () => {
    const { result } = renderHook(() => useSessionWorkflows(''), {
      wrapper: createTestWrapper(),
    });

    expect(result.current.isLoading).toBe(false);
  });
});
