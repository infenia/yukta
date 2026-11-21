// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { ReactFlowProvider } from '@xyflow/react';
import type { NodeProps } from '@xyflow/react';
import { PinnedNodes } from '../../../hooks/pinnedNodes';
import { WorkflowNodeCard } from '../WorkflowNodeCard';
import { createMockGraphNode } from '@/test/factories/workflowFactory';
import type { WorkflowFlowNode } from '../../../lib/layout';

function renderCard(pinned: ReadonlySet<string>) {
  const node = createMockGraphNode();
  const props = {
    id: node.nodeId,
    data: { node, status: 'pending' },
    selected: false,
    dragging: false,
  } as unknown as NodeProps<WorkflowFlowNode>;
  return render(
    <MemoryRouter>
      <ReactFlowProvider>
        <PinnedNodes value={pinned}>
          <WorkflowNodeCard {...props} />
        </PinnedNodes>
      </ReactFlowProvider>
    </MemoryRouter>
  );
}

describe('WorkflowNodeCard pinned output', () => {
  it('marks a node that holds a pinned test output', () => {
    renderCard(new Set(['data-ingress']));
    expect(
      screen.getByRole('img', { name: 'Has a pinned test output' })
    ).toBeInTheDocument();
  });

  it('shows no mark without one', () => {
    renderCard(new Set());
    expect(
      screen.queryByRole('img', { name: 'Has a pinned test output' })
    ).not.toBeInTheDocument();
  });
});
