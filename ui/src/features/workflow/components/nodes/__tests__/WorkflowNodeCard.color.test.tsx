// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { ReactFlowProvider } from '@xyflow/react';
import type { NodeProps } from '@xyflow/react';
import { WorkflowNodeCard } from '../WorkflowNodeCard';
import { createMockGraphNode } from '@/test/factories/workflowFactory';
import type { WorkflowFlowNode, WorkflowNodeData } from '../../../lib/layout';

function renderCard(data: WorkflowNodeData) {
  const props = {
    id: data.node.nodeId,
    data,
    selected: false,
    dragging: false,
  } as unknown as NodeProps<WorkflowFlowNode>;
  return render(
    <MemoryRouter>
      <ReactFlowProvider>
        <WorkflowNodeCard {...props} />
      </ReactFlowProvider>
    </MemoryRouter>
  );
}

describe('WorkflowNodeCard color', () => {
  it('marks a colored failed node with a stripe and wash, and keeps status and category', () => {
    renderCard({
      node: createMockGraphNode({
        nodeId: 'on-fail',
        category: 'PROCESSOR',
        color: 'violet',
      }),
      status: 'failed',
    });

    const card = screen.getByTestId('workflow-node-on-fail');
    expect(screen.getByTestId('node-color-stripe').style.backgroundColor).toBe(
      'var(--pal-violet)'
    );
    expect(card.style.backgroundImage).toContain('var(--pal-violet)');
    // status keeps the border, category keeps the top accent and the handles
    expect(card).toHaveClass('border-error');
    expect(card.style.getPropertyValue('--node-accent')).toBe(
      'var(--color-cat-processor)'
    );
    expect(screen.getByTestId('status-badge')).toHaveTextContent('close');
  });

  it('draws nothing extra without a color or with an unknown one', () => {
    for (const color of [undefined, null, 'chartreuse']) {
      const { unmount } = renderCard({
        node: createMockGraphNode({ nodeId: 'plain', color }),
        status: 'completed',
      });
      expect(screen.queryByTestId('node-color-stripe')).toBeNull();
      expect(
        screen.getByTestId('workflow-node-plain').style.backgroundImage
      ).toBe('');
      unmount();
    }
  });
});
