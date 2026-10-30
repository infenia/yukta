// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render } from '@testing-library/react';
import type { Connection, ReactFlowProps } from '@xyflow/react';
import { WorkflowCanvas } from '../WorkflowCanvas';
import { createMockGraph } from '@/test/factories/workflowFactory';

// ReactFlow is replaced by a stand-in that keeps the props it was given.
let props: ReactFlowProps = {};
vi.mock('@xyflow/react', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@xyflow/react')>()),
  ReactFlow: (given: ReactFlowProps) => {
    props = given;
    return null;
  },
}));

describe('WorkflowCanvas connecting', () => {
  beforeEach(() => {
    props = {};
  });

  it('stays read-only without onConnect', () => {
    render(<WorkflowCanvas graph={createMockGraph()} tasksByNode={{}} />);
    expect(props.nodesConnectable).toBe(false);
    expect(props.nodes?.every((node) => node.connectable === false)).toBe(true);
  });

  it('lets every node be connected, and reports a drawn edge with its port', () => {
    const onConnect = vi.fn();
    render(
      <WorkflowCanvas
        graph={createMockGraph()}
        tasksByNode={{}}
        onConnect={onConnect}
      />
    );
    expect(props.nodesConnectable).toBe(true);
    expect(props.nodes?.every((node) => node.connectable)).toBe(true);

    props.onConnect?.({
      source: 'a',
      target: 'b',
      sourceHandle: 'error',
    } as Connection);
    props.onConnect?.({
      source: 'a',
      target: 'c',
      sourceHandle: null,
    } as Connection);
    expect(onConnect.mock.calls).toEqual([
      ['a', 'b', 'error'],
      ['a', 'c', null],
    ]);
  });
});
