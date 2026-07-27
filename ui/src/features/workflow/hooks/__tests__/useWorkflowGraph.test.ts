// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { useWorkflowGraph } from '../useWorkflowGraph';
import { createTestWrapper } from '@/test/utils/testUtils';
import { createMockGraph } from '@/test/factories/workflowFactory';

const server = setupServer(
  http.get('/api/sessions/:sessionId/workflows/:workflowId/graph', () =>
    HttpResponse.json({
      timestamp: '2026-07-26T00:00:00Z',
      status: 200,
      message: 'Workflow graph retrieved',
      data: createMockGraph(),
      path: '/api/sessions/s1/workflows/wf-982-xk-11/graph',
    })
  )
);

beforeAll(() => server.listen());
afterAll(() => server.close());

describe('useWorkflowGraph', () => {
  it('fetches the graph for a session workflow', async () => {
    const { result } = renderHook(() => useWorkflowGraph('s1', 'wf-982-xk-11'), {
      wrapper: createTestWrapper(),
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.workflowId).toBe('wf-982-xk-11');
    expect(result.current.data?.nodes).toHaveLength(4);
  });

  it('stays disabled without identifiers', () => {
    const { result } = renderHook(() => useWorkflowGraph('', ''), {
      wrapper: createTestWrapper(),
    });

    expect(result.current.fetchStatus).toBe('idle');
    expect(result.current.data).toBeUndefined();
  });
});
