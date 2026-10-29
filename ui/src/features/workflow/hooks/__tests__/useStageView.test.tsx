// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect } from 'vitest';
import { act, renderHook } from '@testing-library/react';
import type { ReactNode } from 'react';
import { MemoryRouter, useLocation } from 'react-router';
import { useStageView } from '../useStageView';
import {
  createMockGraph,
  createMockTask,
  createStagedGraph,
} from '@/test/factories/workflowFactory';
import type { WorkflowGraph } from '../../types/workflow';

const failedFix = {
  'lint.fix': createMockTask({ nodeId: 'lint.fix', status: 'FAILURE' }),
};

function setup(url: string, graph: WorkflowGraph | undefined, tasks = {}) {
  const wrapper = ({ children }: { children: ReactNode }) => (
    <MemoryRouter initialEntries={[url]}>{children}</MemoryRouter>
  );
  return renderHook(
    () => ({ view: useStageView(graph, tasks), location: useLocation() }),
    { wrapper }
  );
}

describe('useStageView', () => {
  it('opens the stages with a failed node until the URL names the open ones', () => {
    const { result } = setup('/w', createStagedGraph(), failedFix);
    expect([...(result.current.view.stageView?.open ?? [])]).toEqual(['lint']);

    act(() => result.current.view.stageView?.onToggle('test'));
    expect(result.current.location.search).toBe('?open=lint%2Ctest');
    act(() => result.current.view.stageView?.onToggle('lint'));
    expect([...(result.current.view.stageView?.open ?? [])]).toEqual(['test']);
    act(() => result.current.view.stageView?.onToggle('test'));
    expect(result.current.location.search).toBe('?open=');
    expect(result.current.view.stageView?.open.size).toBe(0);
  });

  it('turns grouping off and on with ?stages=all, keeping other params', () => {
    const { result } = setup('/w?exec=e1', createStagedGraph());
    expect(result.current.view).toMatchObject({
      grouped: true,
      hasStages: true,
    });
    act(() => result.current.view.setGrouped(false));
    expect(result.current.location.search).toBe('?exec=e1&stages=all');
    expect(result.current.view.stageView).toBeUndefined();
    act(() => result.current.view.setGrouped(true));
    expect(result.current.location.search).toBe('?exec=e1');
  });

  it('has nothing to group in a graph without stages, or before it loads', () => {
    expect(setup('/w', createMockGraph()).result.current.view).toMatchObject({
      hasStages: false,
      stageView: undefined,
    });
    expect(setup('/w', undefined).result.current.view.hasStages).toBe(false);
  });
});
