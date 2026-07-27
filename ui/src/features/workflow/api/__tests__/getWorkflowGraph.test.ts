// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { getWorkflowGraph } from '../getWorkflowGraph';
import { ApiError } from '@/lib/apiClient';
import { createMockGraph } from '@/test/factories/workflowFactory';

const server = setupServer(
  http.get('/api/sessions/:sessionId/workflows/:workflowId/graph', ({ params }) => {
    if (params.workflowId === 'missing') {
      return HttpResponse.json({
        timestamp: '2026-07-26T00:00:00Z',
        status: 404,
        message: 'Not found',
        error: 'Workflow not found',
        path: '/api/sessions/s1/workflows/missing/graph',
      });
    }
    return HttpResponse.json({
      timestamp: '2026-07-26T00:00:00Z',
      status: 200,
      message: 'Workflow graph retrieved',
      data: createMockGraph(),
      path: '/api/sessions/s1/workflows/wf-982-xk-11/graph',
    });
  })
);

beforeAll(() => server.listen());
afterAll(() => server.close());

describe('getWorkflowGraph', () => {
  it('fetches the enriched workflow graph', async () => {
    const result = await getWorkflowGraph('s1', 'wf-982-xk-11');
    expect(result.nodes).toHaveLength(4);
    expect(result.nodes[0].category).toBe('TRIGGER');
    expect(result.edges).toHaveLength(4);
    expect(result.topologicalOrder[0]).toBe('data-ingress');
  });

  it('throws ApiError for a missing workflow', async () => {
    await expect(getWorkflowGraph('s1', 'missing')).rejects.toBeInstanceOf(ApiError);
  });
});
