// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { act, renderHook } from '@testing-library/react';
import {
  LOG_FLUSH_MS,
  MAX_LOG_ENTRIES,
  useExecutionLogs,
} from '../useExecutionLogs';
import { createMockLogEntry } from '@/test/factories/workflowFactory';
import {
  installMockEventSource,
  MockEventSource,
} from '@/test/mocks/MockEventSource';

describe('useExecutionLogs', () => {
  beforeEach(() => {
    installMockEventSource();
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  const flush = () => act(() => vi.advanceTimersByTime(LOG_FLUSH_MS));

  it('does not subscribe without an execution', () => {
    const { result } = renderHook(() => useExecutionLogs('s1', null));

    expect(result.current.entries).toEqual([]);
    expect(result.current.isStreaming).toBe(false);
    expect(MockEventSource.instances).toHaveLength(0);
  });

  it('subscribes to the structured log stream and accumulates entries', () => {
    const { result } = renderHook(() =>
      useExecutionLogs('s1', 'exec-091-qp-55')
    );

    expect(MockEventSource.latest().url).toBe(
      '/api/sessions/s1/executions/exec-091-qp-55/logs/entries'
    );

    act(() => {
      MockEventSource.latest().open();
      MockEventSource.latest().emit(createMockLogEntry({ message: 'first' }));
      MockEventSource.latest().emit(
        createMockLogEntry({ message: 'second', level: 'WARN' })
      );
    });

    expect(result.current.entries).toEqual([]); // batched until the flush interval
    flush();
    expect(result.current.isStreaming).toBe(true);
    expect(result.current.entries.map((entry) => entry.message)).toEqual([
      'first',
      'second',
    ]);
  });

  it('caps the buffer at MAX_LOG_ENTRIES', () => {
    const { result } = renderHook(() =>
      useExecutionLogs('s1', 'exec-091-qp-55')
    );

    act(() => {
      const source = MockEventSource.latest();
      for (let i = 0; i < MAX_LOG_ENTRIES + 5; i += 1) {
        source.emit(createMockLogEntry({ message: `line ${i}` }));
      }
    });

    flush();
    expect(result.current.entries).toHaveLength(MAX_LOG_ENTRIES);
    expect(result.current.entries[0].message).toBe('line 5');
    expect(result.current.dropped).toBe(5);
  });

  it('resets the buffer and resubscribes when the execution changes', () => {
    const { result, rerender } = renderHook(
      ({ executionId }) => useExecutionLogs('s1', executionId),
      { initialProps: { executionId: 'exec-1' as string | null } }
    );

    act(() =>
      MockEventSource.latest().emit(createMockLogEntry({ message: 'old' }))
    );
    flush();
    expect(result.current.entries).toHaveLength(1);

    rerender({ executionId: 'exec-2' });
    expect(result.current.entries).toEqual([]);
    expect(MockEventSource.latest().url).toContain('exec-2');
    expect(MockEventSource.instances[0].readyState).toBe(
      MockEventSource.CLOSED
    );
  });

  it('stops streaming when the server ends the stream of a finished execution', () => {
    const { result } = renderHook(() =>
      useExecutionLogs('s1', 'exec-091-qp-55', false)
    );

    act(() => {
      MockEventSource.latest().emit(createMockLogEntry({ message: 'kept' }));
      MockEventSource.latest().emitError();
    });

    flush();
    expect(result.current.isStreaming).toBe(false);
    expect(result.current.entries).toHaveLength(1);
  });

  it('reconnects while live and drops the history replayed by the server', () => {
    const { result } = renderHook(() =>
      useExecutionLogs('s1', 'exec-091-qp-55')
    );

    act(() => {
      MockEventSource.latest().emit(createMockLogEntry({ message: 'a' }));
      MockEventSource.latest().emit(createMockLogEntry({ message: 'b' }));
      MockEventSource.latest().emitError();
    });
    flush();
    act(() => vi.advanceTimersByTime(1000));
    expect(MockEventSource.instances).toHaveLength(2);

    act(() => {
      MockEventSource.latest().emit(createMockLogEntry({ message: 'a' }));
      MockEventSource.latest().emit(createMockLogEntry({ message: 'b' }));
      MockEventSource.latest().emit(createMockLogEntry({ message: 'c' }));
    });
    flush();
    expect(result.current.entries.map((entry) => entry.message)).toEqual([
      'a',
      'b',
      'c',
    ]);
  });

  it('clears entries on demand', () => {
    const { result } = renderHook(() =>
      useExecutionLogs('s1', 'exec-091-qp-55')
    );

    act(() => MockEventSource.latest().emit(createMockLogEntry()));
    flush();
    expect(result.current.entries).toHaveLength(1);

    act(() => result.current.clear());
    expect(result.current.entries).toEqual([]);
  });

  it('does not bring back a line that was waiting for the flush when it was cleared', () => {
    const { result } = renderHook(() =>
      useExecutionLogs('s1', 'exec-091-qp-55')
    );

    act(() =>
      MockEventSource.latest().emit(createMockLogEntry({ message: 'waiting' }))
    );
    act(() => result.current.clear());
    flush();
    expect(result.current.entries).toEqual([]);
  });

  it('keeps cleared lines away when the server replays its history', () => {
    const { result } = renderHook(() =>
      useExecutionLogs('s1', 'exec-091-qp-55')
    );

    act(() => {
      MockEventSource.latest().emit(createMockLogEntry({ message: 'old' }));
      MockEventSource.latest().emitError();
    });
    flush();
    act(() => result.current.clear());
    act(() => vi.advanceTimersByTime(1000));

    act(() => {
      MockEventSource.latest().emit(createMockLogEntry({ message: 'old' }));
      MockEventSource.latest().emit(createMockLogEntry({ message: 'new' }));
    });
    flush();
    expect(result.current.entries.map((entry) => entry.message)).toEqual([
      'new',
    ]);
  });
});
