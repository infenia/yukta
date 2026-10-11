// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { act, renderHook, waitFor } from '@testing-library/react';
import { ApiError } from '@/lib/apiClient';
import { createTestWrapper } from '@/test/utils/testUtils';
import * as api from '../../api/executionControl';
import { controlErrorMessage, useExecutionControl } from '../useExecutionControl';

vi.mock('../../api/executionControl');

beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(api.startWorkflow).mockResolvedValue({ executionId: 'e9' });
  vi.mocked(api.runAction).mockResolvedValue({ executionId: 'e1' });
  vi.mocked(api.stopWorkflow).mockResolvedValue({ executionIds: [] });
  vi.mocked(api.restartFromNode).mockResolvedValue({ executionId: 'e2' });
  vi.mocked(api.nodeAction).mockResolvedValue({ executionId: 'e1' });
});

describe('controlErrorMessage', () => {
  it('explains a scheduler 429, passes other messages through and has a fallback', () => {
    expect(controlErrorMessage(new ApiError(429, 'busy'))).toMatch(/scheduler/i);
    expect(controlErrorMessage(new ApiError(500, 'bad'))).toBe('bad');
    expect(controlErrorMessage('x')).toBe('Request failed');
  });
});

describe('useExecutionControl', () => {
  it('forwards each mutation to the api with its arguments', async () => {
    const { result } = renderHook(() => useExecutionControl('s1', 'wf'), {
      wrapper: createTestWrapper(),
    });

    await act(() => result.current.start.mutateAsync({ a: 1 }));
    expect(api.startWorkflow).toHaveBeenCalledWith('s1', 'wf', { a: 1 }, expect.any(String));

    await act(() => result.current.runAction.mutateAsync({ executionId: 'e1', action: 'pause' }));
    expect(api.runAction).toHaveBeenCalledWith('e1', 'pause');

    await act(() => result.current.stopAll.mutateAsync('DRAIN'));
    expect(api.stopWorkflow).toHaveBeenCalledWith('s1', 'wf', 'DRAIN');

    await act(() =>
      result.current.restartFromNode.mutateAsync({ executionId: 'e1', nodeId: 'n' })
    );
    expect(api.restartFromNode).toHaveBeenCalledWith('e1', 'n');

    await act(() =>
      result.current.nodeAction.mutateAsync({ executionId: 'e1', nodeId: 'n', action: 'skip' })
    );
    await waitFor(() => expect(api.nodeAction).toHaveBeenCalledWith('e1', 'n', 'skip'));
  });
});
