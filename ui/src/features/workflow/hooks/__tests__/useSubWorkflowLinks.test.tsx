// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { QueryClientProvider } from '@tanstack/react-query';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { createTestQueryClient } from '@/test/utils/testUtils';
import {
  createMockExecution,
  createMockGraph,
  createStagedGraph,
} from '@/test/factories/workflowFactory';
import { useSubWorkflowLinks } from '../useSubWorkflowLinks';

const run = (
  executionId: string,
  workflowId: string,
  parentExecutionId: string | null
) => ({
  ...createMockExecution({ executionId, workflowId }),
  parentExecutionId,
});
let trees = 0;
const server = setupServer(
  http.get('/api/executions/:id/tree', () => {
    trees++;
    return HttpResponse.json({
      timestamp: 't',
      status: 200,
      message: 'ok',
      path: '/',
      data: [
        run('e1', 'gate', null),
        run('c1', 'fix', 'e1'),
        run('g1', 'fix', 'c1'), // a grandchild is not this run's
        run('c2', 'fix', 'e1'),
      ],
    });
  })
);
beforeAll(() => server.listen());
afterEach(() => {
  trees = 0;
});
afterAll(() => server.close());

const wrapper = ({ children }: { children: ReactNode }) => (
  <QueryClientProvider client={createTestQueryClient()}>
    {children}
  </QueryClientProvider>
);

describe('useSubWorkflowLinks', () => {
  it('opens the newest run a sub-workflow node started in this execution', async () => {
    const { result } = renderHook(
      () => useSubWorkflowLinks('s 1', createStagedGraph(), 'e1', true),
      { wrapper }
    );
    await waitFor(() =>
      expect(result.current.get('lint.fix')).toBe(
        '/sessions/s%201/workflow/fix?exec=c2'
      )
    );
    expect([...result.current.keys()]).toEqual(['lint.fix']);
  });

  it('opens the workflow itself when there is no run, without asking for a tree', () => {
    const { result } = renderHook(
      () => useSubWorkflowLinks('s1', createStagedGraph(), null, false),
      { wrapper }
    );
    expect(result.current.get('lint.fix')).toBe('/sessions/s1/workflow/fix');
    expect(trees).toBe(0);
  });

  it('has no links, and asks for no tree, in a workflow without sub-workflows', () => {
    const { result } = renderHook(
      () => useSubWorkflowLinks('s1', createMockGraph(), 'e1', false),
      { wrapper }
    );
    expect(result.current.size).toBe(0);
    expect(
      renderHook(() => useSubWorkflowLinks('s1', undefined, 'e1', false), {
        wrapper,
      }).result.current.size
    ).toBe(0);
    expect(trees).toBe(0);
  });
});
