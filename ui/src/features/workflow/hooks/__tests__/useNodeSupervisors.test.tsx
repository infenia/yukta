// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { QueryClientProvider } from '@tanstack/react-query';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { createTestQueryClient } from '@/test/utils/testUtils';
import { useNodeSupervisors } from '../useNodeSupervisors';

const server = setupServer(
  http.get('/api/supervision/ownership', () =>
    HttpResponse.json({
      timestamp: 't',
      status: 200,
      message: 'ok',
      path: '/',
      data: {
        run: 'default',
        defaultSupervisor: 'default',
        nodes: { ingest: 'ops-supervisor', sink: 'default' },
      },
    })
  )
);
beforeAll(() => server.listen());
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

const wrapper = ({ children }: { children: ReactNode }) => (
  <QueryClientProvider client={createTestQueryClient()}>
    {children}
  </QueryClientProvider>
);

describe('useNodeSupervisors', () => {
  it('has no supervisors until the ownership arrives', () => {
    const { result } = renderHook(() => useNodeSupervisors('s1', 'w1'), {
      wrapper,
    });
    expect(result.current.size).toBe(0);
  });

  it('maps each node to its supervisor and leaves out the nodes nobody supervises', async () => {
    const { result } = renderHook(() => useNodeSupervisors('s1', 'w1'), {
      wrapper,
    });
    await waitFor(() =>
      expect(result.current.get('ingest')).toBe('ops-supervisor')
    );
    expect([...result.current.keys()]).toEqual(['ingest']);
  });
});
