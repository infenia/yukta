// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render } from '@testing-library/react';
import type { ReactFlowProps } from '@xyflow/react';
import { WorkflowCanvas } from '../WorkflowCanvas';
import { EdgeAdder } from '../../lib/stepAdders';
import { InsertableEdge } from '../edges/InsertableEdge';
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

describe('WorkflowCanvas edges the editor can add to', () => {
  beforeEach(() => {
    props = {};
  });

  it('draws the edges as they are in the run view, which adds nothing to them', () => {
    render(<WorkflowCanvas graph={createMockGraph()} tasksByNode={{}} />);
    expect(props.edges?.length).toBeGreaterThan(0);
    expect(props.edges?.every((edge) => edge.type === 'default')).toBe(true);
  });

  it('draws them with the insertable edge when the editor gives a way to add a step', () => {
    render(
      <EdgeAdder.Provider value={() => null}>
        <WorkflowCanvas graph={createMockGraph()} tasksByNode={{}} />
      </EdgeAdder.Provider>
    );
    expect(props.edges?.every((edge) => edge.type === 'insertable')).toBe(true);
    expect(props.edgeTypes?.insertable).toBe(InsertableEdge);
  });
});
