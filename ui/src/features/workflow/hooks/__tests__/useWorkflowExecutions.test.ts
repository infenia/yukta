// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { useWorkflowExecutions } from '../useWorkflowExecutions';
import { createTestWrapper } from '@/test/utils/testUtils';
import { createMockExecution } from '@/test/factories/workflowFactory';

const server = setupServer(
  http.get('/api/sessions/:sessionId/workflows/:workflowId/executions', () =>
    HttpResponse.json({
      timestamp: '2026-07-26T00:00:00Z',
      status: 200,
      message: 'Workflow executions retrieved',
      data: { executions: [createMockExecution()] },
      path: '/api/sessions/s1/workflows/wf-982-xk-11/executions',
    })
  )
);

beforeAll(() => server.listen());
afterAll(() => server.close());

describe('useWorkflowExecutions', () => {
  it('fetches and unwraps the executions collection', async () => {
    const { result } = renderHook(() => useWorkflowExecutions('s1', 'wf-982-xk-11'), {
      wrapper: createTestWrapper(),
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toHaveLength(1);
    expect(result.current.data?.[0].executionId).toBe('exec-091-qp-55');
  });

  it('stays disabled without identifiers', () => {
    const { result } = renderHook(() => useWorkflowExecutions('s1', ''), {
      wrapper: createTestWrapper(),
    });

    expect(result.current.fetchStatus).toBe('idle');
  });
});
