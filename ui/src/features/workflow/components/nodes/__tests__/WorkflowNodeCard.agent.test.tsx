// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { ReactFlowProvider } from '@xyflow/react';
import type { NodeProps } from '@xyflow/react';
import { WorkflowNodeCard } from '../WorkflowNodeCard';
import { AgentsByNode } from '../../../lib/agentsByNode';
import { createMockGraphNode } from '@/test/factories/workflowFactory';
import { createConversation } from '@/test/factories/conversationFactory';
import type { WorkflowFlowNode } from '../../../lib/layout';

function renderCard(
  agents: Parameters<typeof AgentsByNode.Provider>[0]['value']
) {
  const data = {
    node: createMockGraphNode({ nodeId: 'review' }),
    status: 'running' as const,
  };
  const props = {
    id: 'review',
    data,
    selected: false,
    dragging: false,
  } as unknown as NodeProps<WorkflowFlowNode>;
  return render(
    <MemoryRouter>
      <ReactFlowProvider>
        <AgentsByNode.Provider value={agents}>
          <WorkflowNodeCard {...props} />
        </AgentsByNode.Provider>
      </ReactFlowProvider>
    </MemoryRouter>
  );
}

describe('WorkflowNodeCard of an agent node', () => {
  it('says that the agent waits for you', () => {
    renderCard({ review: createConversation({ state: 'WAITING' }) });
    expect(screen.getByTestId('agent-state')).toHaveTextContent(
      'Waiting for you'
    );
  });

  it('says that the agent works', () => {
    renderCard({ review: createConversation({ state: 'RUNNING' }) });
    expect(screen.getByTestId('agent-state')).toHaveTextContent('Working');
  });

  it('shows nothing for an agent that has ended, or for no agent', () => {
    const { unmount } = renderCard({
      review: createConversation({
        state: 'ENDED',
        outcome: 'success',
        endedAt: 't',
      }),
    });
    expect(screen.queryByTestId('agent-state')).not.toBeInTheDocument();
    unmount();

    renderCard({});
    expect(screen.queryByTestId('agent-state')).not.toBeInTheDocument();
  });
});
