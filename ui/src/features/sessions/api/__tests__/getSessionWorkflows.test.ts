// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { getSessionWorkflows } from '../getSessionWorkflows';

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

beforeAll(() => server.listen());
afterAll(() => server.close());

describe('getSessionWorkflows', () => {
  it('fetches workflow summaries successfully', async () => {
    const result = await getSessionWorkflows('sess-123');
    expect(result.workflows).toHaveLength(1);
    expect(result.workflows[0].workflowId).toBe('wf1');
    expect(result.workflows[0].nodeCount).toBe(5);
  });
});
