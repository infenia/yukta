// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeAll, afterAll, beforeEach } from 'vitest';
import { act, renderHook, waitFor } from '@testing-library/react';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { useExecutionProgress } from '../useExecutionProgress';
import { createTestQueryClient, createTestWrapper } from '@/test/utils/testUtils';
import { createMockProgress } from '@/test/factories/workflowFactory';
import { installMockEventSource, MockEventSource } from '@/test/mocks/MockEventSource';

const server = setupServer(
  http.get('/api/workflow/:sessionId/status/:executionId', ({ params }) =>
    HttpResponse.json({
      timestamp: '2026-07-26T00:00:00Z',
      status: 200,
      message: 'Workflow status retrieved',
      data: createMockProgress(
        params.executionId === 'exec-done'
          ? { executionId: 'exec-done', status: 'COMPLETED', endTime: '2026-07-26T14:25:00' }
          : {}
      ),
      path: '/api/workflow/s1/status/x',
    })
  )
);

beforeAll(() => server.listen());
afterAll(() => server.close());

describe('useExecutionProgress', () => {
  beforeEach(() => {
    installMockEventSource();
  });

  it('returns null progress when no execution is selected', () => {
    const { result } = renderHook(() => useExecutionProgress('s1', null), {
      wrapper: createTestWrapper(),
    });

    expect(result.current.progress).toBeNull();
    expect(result.current.isStreaming).toBe(false);
    expect(MockEventSource.instances).toHaveLength(0);
  });

  it('fetches the snapshot and opens the SSE stream while running', async () => {
    const { result } = renderHook(() => useExecutionProgress('s1', 'exec-091-qp-55'), {
      wrapper: createTestWrapper(),
    });

    await waitFor(() => expect(result.current.progress).not.toBeNull());
    expect(result.current.progress?.status).toBe('RUNNING');

    await waitFor(() => expect(MockEventSource.instances).toHaveLength(1));
    expect(MockEventSource.latest().url).toBe(
      '/api/workflow/s1/status/exec-091-qp-55/stream?includeHistory=true'
    );
    expect(result.current.isStreaming).toBe(true);
  });

  it('does not stream when the execution is already terminal', async () => {
    const { result } = renderHook(() => useExecutionProgress('s1', 'exec-done'), {
      wrapper: createTestWrapper(),
    });

    await waitFor(() => expect(result.current.progress).not.toBeNull());
    expect(result.current.progress?.status).toBe('COMPLETED');
    expect(MockEventSource.instances).toHaveLength(0);
    expect(result.current.isStreaming).toBe(false);
  });

  it('merges live updates and closes the stream on a terminal update', async () => {
    const queryClient = createTestQueryClient();
    const { result } = renderHook(() => useExecutionProgress('s1', 'exec-091-qp-55'), {
      wrapper: createTestWrapper(queryClient),
    });

    await waitFor(() => expect(MockEventSource.instances).toHaveLength(1));

    act(() =>
      MockEventSource.latest().emit(
        createMockProgress({
          tasks: createMockProgress().tasks.map((task) => ({ ...task, status: 'SUCCESS' })),
        })
      )
    );
    expect(result.current.progress?.tasks.every((task) => task.status === 'SUCCESS')).toBe(true);

    act(() =>
      MockEventSource.latest().emit(
        createMockProgress({ status: 'COMPLETED', endTime: '2026-07-26T14:25:00' })
      )
    );

    await waitFor(() => expect(result.current.isStreaming).toBe(false));
    expect(result.current.progress?.status).toBe('COMPLETED');
    expect(MockEventSource.latest().readyState).toBe(MockEventSource.CLOSED);
  });

  it('ignores stream updates for other executions', async () => {
    const { result } = renderHook(() => useExecutionProgress('s1', 'exec-091-qp-55'), {
      wrapper: createTestWrapper(),
    });

    await waitFor(() => expect(MockEventSource.instances).toHaveLength(1));

    act(() =>
      MockEventSource.latest().emit(
        createMockProgress({ executionId: 'exec-other', status: 'COMPLETED' })
      )
    );
    expect(result.current.progress?.executionId).toBe('exec-091-qp-55');
    expect(result.current.progress?.status).toBe('RUNNING');
  });

  it('resets live progress when the execution changes', async () => {
    const { result, rerender } = renderHook(
      ({ executionId }) => useExecutionProgress('s1', executionId),
      {
        wrapper: createTestWrapper(),
        initialProps: { executionId: 'exec-091-qp-55' as string | null },
      }
    );

    await waitFor(() => expect(MockEventSource.instances).toHaveLength(1));
    act(() => MockEventSource.latest().emit(createMockProgress({ status: 'PAUSED' })));
    expect(result.current.progress?.status).toBe('PAUSED');

    rerender({ executionId: 'exec-done' });
    await waitFor(() => expect(result.current.progress?.executionId).toBe('exec-done'));
    expect(result.current.progress?.status).toBe('COMPLETED');
  });
});
