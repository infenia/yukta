// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { ReactFlowProvider } from '@xyflow/react';
import type { NodeProps } from '@xyflow/react';
import { WorkflowNodeCard } from '../WorkflowNodeCard';
import { createMockGraphNode } from '@/test/factories/workflowFactory';
import type { WorkflowFlowNode } from '../../../lib/layout';

function renderCard(issues?: { errors: number; warnings: number }) {
  const data = {
    node: createMockGraphNode({ nodeId: 'n', category: 'PROCESSOR', issues }),
    status: 'pending',
  };
  const props = {
    id: 'n',
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

describe('WorkflowNodeCard issues badge', () => {
  it('shows the error count, and says both kinds in its label', () => {
    renderCard({ errors: 2, warnings: 1 });
    const badge = screen.getByTestId('node-issues-n');
    expect(badge).toHaveTextContent('2');
    expect(badge).toHaveAccessibleName('2 errors, 1 warning');
    expect(badge).toHaveClass('border-error');
  });

  it('shows the warning count when there are no errors', () => {
    renderCard({ errors: 0, warnings: 3 });
    const badge = screen.getByTestId('node-issues-n');
    expect(badge).toHaveTextContent('3');
    expect(badge).toHaveAccessibleName('3 warnings');
    expect(badge).toHaveClass('border-warning');
  });

  it('says "1 error" in the singular', () => {
    renderCard({ errors: 1, warnings: 0 });
    expect(screen.getByTestId('node-issues-n')).toHaveAccessibleName('1 error');
  });

  it('draws nothing for a node without issues', () => {
    renderCard();
    expect(screen.queryByTestId('node-issues-n')).toBeNull();
    renderCard({ errors: 0, warnings: 0 });
    expect(screen.queryByTestId('node-issues-n')).toBeNull();
  });
});
