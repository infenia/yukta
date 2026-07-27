// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { getWorkflowExecutions } from '../getWorkflowExecutions';
import { ApiError } from '@/lib/apiClient';
import { createMockExecution } from '@/test/factories/workflowFactory';

const server = setupServer(
  http.get('/api/sessions/:sessionId/workflows/:workflowId/executions', ({ params }) => {
    if (params.workflowId === 'missing') {
      return HttpResponse.json({
        timestamp: '2026-07-26T00:00:00Z',
        status: 404,
        message: 'Not found',
        error: 'Workflow not found',
        path: '/api/sessions/s1/workflows/missing/executions',
      });
    }
    return HttpResponse.json({
      timestamp: '2026-07-26T00:00:00Z',
      status: 200,
      message: 'Workflow executions retrieved',
      data: {
        executions: [
          createMockExecution(),
          createMockExecution({
            executionId: 'exec-old-1',
            status: 'SUCCESS',
            endTime: '2026-07-25T10:00:00',
          }),
        ],
      },
      path: '/api/sessions/s1/workflows/wf-982-xk-11/executions',
    });
  })
);

beforeAll(() => server.listen());
afterAll(() => server.close());

describe('getWorkflowExecutions', () => {
  it('fetches the per-workflow execution history', async () => {
    const result = await getWorkflowExecutions('s1', 'wf-982-xk-11');
    expect(result.executions).toHaveLength(2);
    expect(result.executions[0].executionId).toBe('exec-091-qp-55');
  });

  it('throws ApiError for a missing workflow', async () => {
    await expect(getWorkflowExecutions('s1', 'missing')).rejects.toBeInstanceOf(ApiError);
  });
});
