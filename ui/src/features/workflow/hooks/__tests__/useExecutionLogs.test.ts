// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeEach } from 'vitest';
import { act, renderHook } from '@testing-library/react';
import { MAX_LOG_ENTRIES, useExecutionLogs } from '../useExecutionLogs';
import { createMockLogEntry } from '@/test/factories/workflowFactory';
import { installMockEventSource, MockEventSource } from '@/test/mocks/MockEventSource';

describe('useExecutionLogs', () => {
  beforeEach(() => {
    installMockEventSource();
  });

  it('does not subscribe without an execution', () => {
    const { result } = renderHook(() => useExecutionLogs('s1', null));

    expect(result.current.entries).toEqual([]);
    expect(result.current.isStreaming).toBe(false);
    expect(MockEventSource.instances).toHaveLength(0);
  });

  it('subscribes to the structured log stream and accumulates entries', () => {
    const { result } = renderHook(() => useExecutionLogs('s1', 'exec-091-qp-55'));

    expect(MockEventSource.latest().url).toBe(
      '/api/sessions/s1/executions/exec-091-qp-55/logs/entries'
    );

    act(() => {
      MockEventSource.latest().open();
      MockEventSource.latest().emit(createMockLogEntry({ message: 'first' }));
      MockEventSource.latest().emit(createMockLogEntry({ message: 'second', level: 'WARN' }));
    });

    expect(result.current.isStreaming).toBe(true);
    expect(result.current.entries.map((entry) => entry.message)).toEqual(['first', 'second']);
  });

  it('caps the buffer at MAX_LOG_ENTRIES', () => {
    const { result } = renderHook(() => useExecutionLogs('s1', 'exec-091-qp-55'));

    act(() => {
      const source = MockEventSource.latest();
      for (let i = 0; i < MAX_LOG_ENTRIES + 5; i += 1) {
        source.emit(createMockLogEntry({ message: `line ${i}` }));
      }
    });

    expect(result.current.entries).toHaveLength(MAX_LOG_ENTRIES);
    expect(result.current.entries[0].message).toBe('line 5');
  });

  it('resets the buffer and resubscribes when the execution changes', () => {
    const { result, rerender } = renderHook(
      ({ executionId }) => useExecutionLogs('s1', executionId),
      { initialProps: { executionId: 'exec-1' as string | null } }
    );

    act(() => MockEventSource.latest().emit(createMockLogEntry({ message: 'old' })));
    expect(result.current.entries).toHaveLength(1);

    rerender({ executionId: 'exec-2' });
    expect(result.current.entries).toEqual([]);
    expect(MockEventSource.latest().url).toContain('exec-2');
    expect(MockEventSource.instances[0].readyState).toBe(MockEventSource.CLOSED);
  });

  it('stops streaming when the server ends the stream', () => {
    const { result } = renderHook(() => useExecutionLogs('s1', 'exec-091-qp-55'));

    act(() => {
      MockEventSource.latest().emit(createMockLogEntry({ message: 'kept' }));
      MockEventSource.latest().emitError();
    });

    expect(result.current.isStreaming).toBe(false);
    expect(result.current.entries).toHaveLength(1);
  });

  it('clears entries on demand', () => {
    const { result } = renderHook(() => useExecutionLogs('s1', 'exec-091-qp-55'));

    act(() => MockEventSource.latest().emit(createMockLogEntry()));
    expect(result.current.entries).toHaveLength(1);

    act(() => result.current.clear());
    expect(result.current.entries).toEqual([]);
  });
});
