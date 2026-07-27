// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { getWorkflowProgress } from '../getWorkflowProgress';
import { ApiError } from '@/lib/apiClient';
import { createMockProgress } from '@/test/factories/workflowFactory';

const server = setupServer(
  http.get('/api/workflow/:sessionId/status/:executionId', ({ params }) => {
    if (params.executionId === 'missing') {
      return HttpResponse.json({
        timestamp: '2026-07-26T00:00:00Z',
        status: 404,
        message: 'Not found',
        error: 'Execution not found',
        path: '/api/workflow/s1/status/missing',
      });
    }
    return HttpResponse.json({
      timestamp: '2026-07-26T00:00:00Z',
      status: 200,
      message: 'Workflow status retrieved',
      data: createMockProgress(),
      path: '/api/workflow/s1/status/exec-091-qp-55',
    });
  })
);

beforeAll(() => server.listen());
afterAll(() => server.close());

describe('getWorkflowProgress', () => {
  it('fetches the execution progress snapshot', async () => {
    const result = await getWorkflowProgress('s1', 'exec-091-qp-55');
    expect(result.status).toBe('RUNNING');
    expect(result.tasks).toHaveLength(4);
    expect(result.tasks[1].status).toBe('RUNNING');
  });

  it('throws ApiError for a missing execution', async () => {
    await expect(getWorkflowProgress('s1', 'missing')).rejects.toBeInstanceOf(ApiError);
  });
});
