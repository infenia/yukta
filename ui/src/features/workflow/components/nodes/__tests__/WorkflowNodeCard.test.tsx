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
    expect(card).toHaveTextContent('Done');
    expect(card).not.toHaveTextContent('100%');
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

  it('labels each port only when a node has several outputs', () => {
    renderCard({
      node: createMockGraphNode({
        nodeId: 'check',
        category: 'PROCESSOR',
        outputPorts: ['default', 'invalid'],
      }),
      status: 'pending',
    });
    expect(screen.getAllByTestId('port-label').map((label) => label.textContent)).toEqual([
      'default',
      'invalid',
    ]);
  });

  it('shows no port label for a single output', () => {
    renderCard({
      node: createMockGraphNode({ nodeId: 'one', category: 'PROCESSOR', outputPorts: ['default'] }),
      status: 'pending',
    });
    expect(screen.queryByTestId('port-label')).toBeNull();
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
    expect(screen.getByTestId('node-icon')).toHaveTextContent('flag');
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

  it('shows no progress text while running without a progress metric', () => {
    renderCard({
      node: createMockGraphNode({ nodeId: 'p2', category: 'PROCESSOR' }),
      status: 'running',
      task: createMockTask({ status: 'RUNNING', metadata: {} }),
    });

    expect(screen.getByTestId('workflow-node-p2')).not.toHaveTextContent('%');
  });

  it('gives a terminal no output handle even if it declares ports', () => {
    renderCard({
      node: createMockGraphNode({ nodeId: 'sink-2', category: 'TERMINAL', outputPorts: ['default'] }),
      status: 'pending',
    });

    expect(screen.getByTestId('workflow-node-sink-2').querySelectorAll('.source')).toHaveLength(0);
  });

  it('uses a plugin-specific icon and shows the plugin name only when the id differs', () => {
    renderCard({
      node: createMockGraphNode({ nodeId: 'my-step', type: 'HTTP_REQUEST', category: 'PROCESSOR' }),
      status: 'pending',
    });

    expect(screen.getByTestId('node-icon')).toHaveTextContent('http');
    expect(screen.getByTestId('workflow-node-my-step')).toHaveTextContent('Http Request');
  });

  it('does not repeat the plugin name for default ids', () => {
    renderCard({
      node: createMockGraphNode({ nodeId: 'HTTP_REQUEST-1', type: 'HTTP_REQUEST', category: 'PROCESSOR' }),
      status: 'pending',
    });

    expect(screen.getByTestId('workflow-node-HTTP_REQUEST-1')).not.toHaveTextContent('Http Request');
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
    expect(screen.getByTestId('workflow-node-p4')).toHaveTextContent('Failed');
    expect(screen.getByTestId('workflow-node-p4')).toHaveClass('border-error');
  });

  it('uses a generic icon for nodes with unknown plugin category', () => {
    renderCard({
      node: createMockGraphNode({ nodeId: 'mystery', category: null }),
      status: 'pending',
    });

    expect(screen.getByTestId('node-icon')).toHaveTextContent('extension');
    expect(screen.getByTestId('workflow-node-mystery')).toHaveTextContent('Waiting');
  });

  it('is keyboard focusable', () => {
    renderCard({ node: createMockGraphNode(), status: 'pending' });
    expect(screen.getByTestId('workflow-node-data-ingress')).toHaveAttribute('tabindex', '0');
  });
});
