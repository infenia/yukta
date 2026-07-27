// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeEach, vi } from 'vitest';
import { act, renderHook } from '@testing-library/react';
import { useEventSourceJson } from '../useEventSourceJson';
import { installMockEventSource, MockEventSource } from '@/test/mocks/MockEventSource';

describe('useEventSourceJson', () => {
  beforeEach(() => {
    installMockEventSource();
  });

  it('stays idle without a url', () => {
    const onMessage = vi.fn();
    const { result } = renderHook(() => useEventSourceJson(null, onMessage));

    expect(result.current.status).toBe('idle');
    expect(MockEventSource.instances).toHaveLength(0);
  });

  it('connects and reports open', () => {
    const { result } = renderHook(() => useEventSourceJson('/api/stream', vi.fn()));

    expect(result.current.status).toBe('connecting');
    act(() => MockEventSource.latest().open());
    expect(result.current.status).toBe('open');
    expect(MockEventSource.latest().url).toBe('/api/stream');
  });

  it('parses JSON frames and forwards them to the handler', () => {
    const onMessage = vi.fn();
    renderHook(() => useEventSourceJson<{ value: number }>('/api/stream', onMessage));

    act(() => MockEventSource.latest().emit({ value: 42 }));
    expect(onMessage).toHaveBeenCalledWith({ value: 42 });
  });

  it('drops malformed frames without breaking the stream', () => {
    const onMessage = vi.fn();
    renderHook(() => useEventSourceJson('/api/stream', onMessage));

    act(() => {
      MockEventSource.latest().emit('{not-json');
      MockEventSource.latest().emit({ ok: true });
    });
    expect(onMessage).toHaveBeenCalledTimes(1);
    expect(onMessage).toHaveBeenCalledWith({ ok: true });
  });

  it('closes permanently on error', () => {
    const { result } = renderHook(() => useEventSourceJson('/api/stream', vi.fn()));

    act(() => MockEventSource.latest().emitError());
    expect(result.current.status).toBe('error');
    expect(MockEventSource.latest().readyState).toBe(MockEventSource.CLOSED);
  });

  it('closes the previous stream when the url changes', () => {
    const { rerender } = renderHook(({ url }) => useEventSourceJson(url, vi.fn()), {
      initialProps: { url: '/api/stream-1' as string | null },
    });

    const first = MockEventSource.latest();
    rerender({ url: '/api/stream-2' });

    expect(first.readyState).toBe(MockEventSource.CLOSED);
    expect(MockEventSource.instances).toHaveLength(2);
    expect(MockEventSource.latest().url).toBe('/api/stream-2');
  });

  it('goes idle when the url becomes null', () => {
    const { result, rerender } = renderHook(({ url }) => useEventSourceJson(url, vi.fn()), {
      initialProps: { url: '/api/stream' as string | null },
    });

    rerender({ url: null });
    expect(result.current.status).toBe('idle');
    expect(MockEventSource.instances[0].readyState).toBe(MockEventSource.CLOSED);
  });

  it('closes the stream on unmount', () => {
    const { unmount } = renderHook(() => useEventSourceJson('/api/stream', vi.fn()));

    unmount();
    expect(MockEventSource.latest().readyState).toBe(MockEventSource.CLOSED);
  });

  it('uses the latest handler without resubscribing', () => {
    const first = vi.fn();
    const second = vi.fn();
    const { rerender } = renderHook(({ handler }) => useEventSourceJson('/api/stream', handler), {
      initialProps: { handler: first },
    });

    rerender({ handler: second });
    act(() => MockEventSource.latest().emit({ n: 1 }));

    expect(MockEventSource.instances).toHaveLength(1);
    expect(first).not.toHaveBeenCalled();
    expect(second).toHaveBeenCalledWith({ n: 1 });
  });
});
