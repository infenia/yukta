// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect } from 'vitest';
import { act, renderHook } from '@testing-library/react';
import { MemoryRouter, useSearchParams } from 'react-router';
import type { ReactNode } from 'react';
import { useExecutionSelection } from '../useExecutionSelection';
import { createMockExecution } from '@/test/factories/workflowFactory';

function createRouterWrapper(initialEntry: string) {
  return function RouterWrapper({ children }: { children: ReactNode }) {
    return <MemoryRouter initialEntries={[initialEntry]}>{children}</MemoryRouter>;
  };
}

const executions = [
  createMockExecution(),
  createMockExecution({ executionId: 'exec-old-1', status: 'SUCCESS' }),
];

describe('useExecutionSelection', () => {
  it('defaults to the latest execution without writing the param', () => {
    const { result } = renderHook(
      () => ({
        selection: useExecutionSelection(executions),
        params: useSearchParams()[0],
      }),
      { wrapper: createRouterWrapper('/sessions/s1/workflow/wf1') }
    );

    expect(result.current.selection.selectedExecutionId).toBe('exec-091-qp-55');
    expect(result.current.params.get('exec')).toBeNull();
  });

  it('honours a valid ?exec= param', () => {
    const { result } = renderHook(() => useExecutionSelection(executions), {
      wrapper: createRouterWrapper('/sessions/s1/workflow/wf1?exec=exec-old-1'),
    });

    expect(result.current.selectedExecutionId).toBe('exec-old-1');
  });

  it('falls back to the latest execution for an unknown ?exec= param', () => {
    const { result } = renderHook(() => useExecutionSelection(executions), {
      wrapper: createRouterWrapper('/sessions/s1/workflow/wf1?exec=ghost'),
    });

    expect(result.current.selectedExecutionId).toBe('exec-091-qp-55');
  });

  it('returns null for a never-run workflow', () => {
    const { result } = renderHook(() => useExecutionSelection([]), {
      wrapper: createRouterWrapper('/sessions/s1/workflow/wf1'),
    });

    expect(result.current.selectedExecutionId).toBeNull();
  });

  it('writes the ?exec= param when selecting an execution', () => {
    const { result } = renderHook(
      () => ({
        selection: useExecutionSelection(executions),
        params: useSearchParams()[0],
      }),
      { wrapper: createRouterWrapper('/sessions/s1/workflow/wf1') }
    );

    act(() => result.current.selection.selectExecution('exec-old-1'));

    expect(result.current.selection.selectedExecutionId).toBe('exec-old-1');
    expect(result.current.params.get('exec')).toBe('exec-old-1');
  });
});
