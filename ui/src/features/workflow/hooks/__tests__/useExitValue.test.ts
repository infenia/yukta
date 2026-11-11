// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { EXIT_MS, useExitValue } from '../useExitValue';

describe('useExitValue', () => {
  it('keeps the last value, closing, until the exit animation is over', async () => {
    const { result, rerender } = renderHook(
      ({ value }: { value: string | null }) => useExitValue(value),
      { initialProps: { value: 'a' as string | null } }
    );
    expect(result.current).toEqual({ shown: 'a', closing: false });

    rerender({ value: null });
    expect(result.current).toEqual({ shown: 'a', closing: true });
    await waitFor(() => expect(result.current.shown).toBeNull(), {
      timeout: EXIT_MS * 10,
    });
    expect(result.current.closing).toBe(false);
  });

  it('switches at once to another value, and cancels a close that is reversed', () => {
    const { result, rerender } = renderHook(
      ({ value }: { value: string | null }) => useExitValue(value),
      { initialProps: { value: 'a' as string | null } }
    );
    rerender({ value: 'b' });
    expect(result.current).toEqual({ shown: 'b', closing: false });
    rerender({ value: null });
    rerender({ value: 'b' });
    expect(result.current.closing).toBe(false);
  });
});
