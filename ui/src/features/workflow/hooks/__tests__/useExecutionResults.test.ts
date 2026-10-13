// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeEach } from 'vitest';
import { act, renderHook } from '@testing-library/react';
import { useExecutionResults } from '../useExecutionResults';
import {
  installMockEventSource,
  MockEventSource,
} from '@/test/mocks/MockEventSource';
import type { ResultEvent } from '../../types/workflow';

function event(
  partial: Partial<ResultEvent> & Pick<ResultEvent, 'kind' | 'sequence'>
): ResultEvent {
  return {
    nodeId: 't1',
    payload: null,
    status: null,
    truncated: false,
    timestamp: '2026-01-01T00:00:00Z',
    ...partial,
  };
}

describe('useExecutionResults', () => {
  beforeEach(() => installMockEventSource());

  it('does not subscribe without an execution', () => {
    const { result } = renderHook(() => useExecutionResults('s1', null));

    expect(result.current.results).toEqual([]);
    expect(MockEventSource.instances).toHaveLength(0);
  });

  it('collects results, skips replayed ones and finishes with the execution', () => {
    const { result } = renderHook(() => useExecutionResults('s1', 'e1'));

    expect(MockEventSource.latest().url).toBe(
      '/api/sessions/s1/executions/e1/results'
    );

    act(() => {
      MockEventSource.latest().open();
      MockEventSource.latest().emit(
        event({ kind: 'RESULT', sequence: 0, payload: 'answer' })
      );
      MockEventSource.latest().emit(
        event({ kind: 'RESULT', sequence: 0, payload: 'answer' })
      );
      MockEventSource.latest().emit(
        event({ kind: 'NODE_END', sequence: 1, status: 'SUCCESS' })
      );
    });

    expect(result.current.results.map((r) => r.payload)).toEqual(['answer']);
    expect(result.current.complete).toBe(false);
    expect(result.current.isStreaming).toBe(true);

    act(() =>
      MockEventSource.latest().emit(
        event({
          kind: 'EXECUTION_END',
          sequence: 2,
          nodeId: null,
          status: 'SUCCESS',
        })
      )
    );

    expect(result.current.complete).toBe(true);
    expect(result.current.executionStatus).toBe('SUCCESS');
    expect(result.current.isStreaming).toBe(false);
  });

  it('starts over when the execution changes', () => {
    const { result, rerender } = renderHook(
      ({ id }) => useExecutionResults('s1', id),
      {
        initialProps: { id: 'e1' as string | null },
      }
    );
    act(() =>
      MockEventSource.latest().emit(
        event({ kind: 'RESULT', sequence: 0, payload: 'old' })
      )
    );
    expect(result.current.results).toHaveLength(1);

    rerender({ id: 'e2' });

    expect(result.current.results).toEqual([]);
  });
});
