// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect } from 'vitest';
import { act, renderHook } from '@testing-library/react';
import type { ReactNode } from 'react';
import {
  BreakpointProvider,
  useBreakpoint,
  useBreakpoints,
} from '../BreakpointContext';

const wrapper = ({ children }: { children: ReactNode }) => (
  <BreakpointProvider>{children}</BreakpointProvider>
);

describe('BreakpointContext', () => {
  it('remembers a breakpoint per run and node, and forgets it when cleared', () => {
    const { result } = renderHook(
      () => ({
        store: useBreakpoints(),
        one: useBreakpoint('e1', 'n1'),
        other: useBreakpoint('e2', 'n1'),
      }),
      { wrapper }
    );
    expect(result.current.one).toBeUndefined();

    act(() =>
      result.current.store.set('e1', 'n1', {
        field: 'payload.status',
        equals: 'error',
      })
    );
    expect(result.current.one).toEqual({
      field: 'payload.status',
      equals: 'error',
    });
    expect(result.current.other).toBeUndefined();

    act(() => result.current.store.clear('e1', 'n1'));
    expect(result.current.one).toBeUndefined();
  });

  it('has no breakpoint without a run', () => {
    const { result } = renderHook(() => useBreakpoint(null, 'n1'), { wrapper });
    expect(result.current).toBeUndefined();
  });

  it('is an empty store outside a provider', () => {
    const { result } = renderHook(() => useBreakpoints());
    result.current.set('e1', 'n1', { field: 'f', equals: 'v' });
    result.current.clear('e1', 'n1');
    expect(result.current.get('e1', 'n1')).toBeUndefined();
  });
});
