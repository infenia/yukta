// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { ReactFlowProvider } from '@xyflow/react';
import type { NodeProps } from '@xyflow/react';
import { WorkflowNodeCard } from '../WorkflowNodeCard';
import { NodeAdder } from '../../../lib/stepAdders';
import { createMockGraphNode } from '@/test/factories/workflowFactory';
import type { WorkflowFlowNode } from '../../../lib/layout';
import type { PluginCategory } from '../../../types/workflow';

function renderCard(
  category: PluginCategory,
  adder: React.ContextType<typeof NodeAdder>,
  onClick = vi.fn()
) {
  const props = {
    id: 'n',
    data: {
      node: createMockGraphNode({ nodeId: 'n', category }),
      status: 'pending',
    },
    selected: false,
    dragging: false,
  } as unknown as NodeProps<WorkflowFlowNode>;
  return render(
    <MemoryRouter>
      <ReactFlowProvider>
        <NodeAdder.Provider value={adder}>
          <div onClick={onClick}>
            <WorkflowNodeCard {...props} />
          </div>
        </NodeAdder.Provider>
      </ReactFlowProvider>
    </MemoryRouter>
  );
}

const adder = (node: { nodeId: string }) => (
  <button type="button">add after {node.nodeId}</button>
);

describe('WorkflowNodeCard step adder', () => {
  it('draws what the editor gives under a node that can have a next step', () => {
    renderCard('PROCESSOR', adder);
    expect(
      screen.getByRole('button', { name: 'add after n' })
    ).toBeInTheDocument();
    renderCard('TRIGGER', adder);
    expect(screen.getAllByRole('button', { name: 'add after n' })).toHaveLength(
      2
    );
  });

  it('draws nothing under a terminal, which ends a branch', () => {
    renderCard('TERMINAL', adder);
    expect(screen.queryByRole('button', { name: /add after/ })).toBeNull();
  });

  it('draws nothing where the editor gives nothing, as in the run view', () => {
    renderCard('PROCESSOR', null);
    expect(screen.queryByRole('button', { name: /add after/ })).toBeNull();
  });

  it('is not a click on the node', async () => {
    const onClick = vi.fn();
    renderCard('PROCESSOR', adder, onClick);
    await userEvent.click(screen.getByRole('button', { name: 'add after n' }));
    expect(onClick).not.toHaveBeenCalled();
  });
});
