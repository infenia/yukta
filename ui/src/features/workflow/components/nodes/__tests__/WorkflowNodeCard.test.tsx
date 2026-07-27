// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { ReactFlowProvider } from '@xyflow/react';
import type { NodeProps } from '@xyflow/react';
import { WorkflowNodeCard } from '../WorkflowNodeCard';
import { createMockGraphNode, createMockTask } from '@/test/factories/workflowFactory';
import type { WorkflowFlowNode, WorkflowNodeData } from '../../../lib/layout';

function renderCard(data: WorkflowNodeData) {
  const props = {
    id: data.node.nodeId,
    data,
    selected: false,
    dragging: false,
  } as unknown as NodeProps<WorkflowFlowNode>;
  return render(
    <ReactFlowProvider>
      <WorkflowNodeCard {...props} />
    </ReactFlowProvider>
  );
}

describe('WorkflowNodeCard', () => {
  it('renders a trigger placeholder with icon, name, status and no input port', () => {
    renderCard({ node: createMockGraphNode(), status: 'completed', task: createMockTask() });

    const card = screen.getByTestId('workflow-node-data-ingress');
    expect(card).toHaveAttribute('aria-label', 'data-ingress: Done');
    expect(screen.getByTestId('node-icon')).toHaveTextContent('input');
    expect(card).toHaveTextContent('Status: Done');
    expect(card).toHaveTextContent('100%');
    expect(card.querySelector('.target')).toBeNull();
    expect(card.querySelector('.source')).not.toBeNull();
  });

  it('renders one output handle per port for processors', () => {
    renderCard({
      node: createMockGraphNode({
        nodeId: 'vectorize-batch',
        category: 'PROCESSOR',
        outputPorts: ['default', 'error'],
      }),
      status: 'pending',
    });

    const card = screen.getByTestId('workflow-node-vectorize-batch');
    expect(card.querySelector('.target')).not.toBeNull();
    expect(card.querySelectorAll('.source')).toHaveLength(2);
    expect(card.querySelector('[data-handleid="error"]')).not.toBeNull();
  });

  it('renders no output ports for terminals', () => {
    renderCard({
      node: createMockGraphNode({
        nodeId: 'sink-storage',
        category: 'TERMINAL',
        outputPorts: [],
      }),
      status: 'pending',
    });

    const card = screen.getByTestId('workflow-node-sink-storage');
    expect(card.querySelectorAll('.source')).toHaveLength(0);
    expect(card.querySelector('.target')).not.toBeNull();
    expect(screen.getByTestId('node-icon')).toHaveTextContent('save');
  });

  it('falls back to a default output handle when a non-terminal has no ports', () => {
    renderCard({
      node: createMockGraphNode({ nodeId: 'p1', category: 'PROCESSOR', outputPorts: [] }),
      status: 'pending',
    });

    expect(
      screen.getByTestId('workflow-node-p1').querySelectorAll('.source')
    ).toHaveLength(1);
  });

  it('shows a spinner and metadata progress while running', () => {
    renderCard({
      node: createMockGraphNode({ nodeId: 'vectorize-batch', category: 'PROCESSOR' }),
      status: 'running',
      task: createMockTask({ status: 'RUNNING', metadata: { progress: 42 } }),
    });

    expect(screen.getByTestId('node-icon')).toHaveTextContent('sync');
    expect(screen.getByTestId('node-icon')).toHaveClass('animate-spin');
    expect(screen.getByTestId('workflow-node-vectorize-batch')).toHaveTextContent('42%');
  });

  it('shows Processing... while running without a progress metric', () => {
    renderCard({
      node: createMockGraphNode({ nodeId: 'p2', category: 'PROCESSOR' }),
      status: 'running',
      task: createMockTask({ status: 'RUNNING', metadata: {} }),
    });

    expect(screen.getByTestId('workflow-node-p2')).toHaveTextContent('Processing...');
  });

  it('shows Processing... while running without task data', () => {
    renderCard({
      node: createMockGraphNode({ nodeId: 'p5', category: 'PROCESSOR' }),
      status: 'running',
    });

    expect(screen.getByTestId('workflow-node-p5')).toHaveTextContent('Processing...');
  });

  it('renders percent from the percent metadata key', () => {
    renderCard({
      node: createMockGraphNode({ nodeId: 'p3', category: 'PROCESSOR' }),
      status: 'running',
      task: createMockTask({ status: 'RUNNING', metadata: { percent: 66.4 } }),
    });

    expect(screen.getByTestId('workflow-node-p3')).toHaveTextContent('66%');
  });

  it('marks failed nodes with the error icon and label', () => {
    renderCard({
      node: createMockGraphNode({ nodeId: 'p4', category: 'PROCESSOR' }),
      status: 'failed',
      task: createMockTask({ status: 'FAILURE' }),
    });

    expect(screen.getByTestId('node-icon')).toHaveTextContent('error');
    expect(screen.getByTestId('workflow-node-p4')).toHaveTextContent('Status: Failed');
    expect(screen.getByTestId('workflow-node-p4')).toHaveClass('border-error');
  });

  it('renders the backend details HTML slot when uiDesign is present', () => {
    renderCard({
      node: createMockGraphNode({
        nodeId: 'branch-1',
        uiDesign: { html: '<b>{{nodeId}}</b>', width: 140, height: 30 },
      }),
      status: 'pending',
    });

    expect(screen.getByTestId('node-details-html')).toHaveTextContent('branch-1');
  });

  it('uses a generic icon for nodes with unknown plugin category', () => {
    renderCard({
      node: createMockGraphNode({ nodeId: 'mystery', category: null }),
      status: 'pending',
    });

    expect(screen.getByTestId('node-icon')).toHaveTextContent('extension');
    expect(screen.getByTestId('workflow-node-mystery')).toHaveTextContent('Status: Waiting');
  });

  it('is keyboard focusable', () => {
    renderCard({ node: createMockGraphNode(), status: 'pending' });
    expect(screen.getByTestId('workflow-node-data-ingress')).toHaveAttribute('tabindex', '0');
  });
});
