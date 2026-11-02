// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi, afterEach } from 'vitest';
import { act, renderHook } from '@testing-library/react';
import { useNow } from '../useNow';

afterEach(() => vi.useRealTimers());

describe('useNow', () => {
  it('reads the clock again every interval and stops when unmounted', () => {
    vi.useFakeTimers();
    vi.setSystemTime(1_000);
    const { result, unmount } = renderHook(() => useNow(5_000));
    expect(result.current).toBe(1_000);

    act(() => {
      vi.advanceTimersByTime(5_000);
    });
    expect(result.current).toBe(6_000);

    unmount();
    expect(vi.getTimerCount()).toBe(0);
  });
});
