// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { SubWorkflowLinks } from '../../../hooks/useSubWorkflowLinks';
import { ReactFlowProvider } from '@xyflow/react';
import type { NodeProps } from '@xyflow/react';
import { WorkflowNodeCard } from '../WorkflowNodeCard';
import {
  createMockGraphNode,
  createMockTask,
} from '@/test/factories/workflowFactory';
import type { WorkflowFlowNode, WorkflowNodeData } from '../../../lib/layout';

function renderCard(data: WorkflowNodeData, selected = false) {
  const props = {
    id: data.node.nodeId,
    data,
    selected,
    dragging: false,
  } as unknown as NodeProps<WorkflowFlowNode>;
  return render(
    <MemoryRouter>
      <ReactFlowProvider>
        <SubWorkflowLinks
          value={new Map([['sub', '/sessions/s/workflow/fix']])}
        >
          <WorkflowNodeCard {...props} />
        </SubWorkflowLinks>
      </ReactFlowProvider>
    </MemoryRouter>
  );
}

describe('WorkflowNodeCard', () => {
  it('renders a trigger placeholder with icon, name, status and no input port', () => {
    renderCard({
      node: createMockGraphNode(),
      status: 'completed',
      task: createMockTask(),
    });

    const card = screen.getByTestId('workflow-node-data-ingress');
    expect(card).toHaveAttribute('aria-label', 'data-ingress: Succeeded');
    expect(screen.getByTestId('node-icon')).toHaveTextContent('input');
    expect(card).not.toHaveTextContent('Succeeded');
    expect(card).not.toHaveTextContent('100%');
    expect(screen.getByTestId('status-badge')).toHaveTextContent('check');
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
    expect(
      screen.getAllByTestId('port-label').map((label) => label.textContent)
    ).toEqual(['default', 'invalid']);
  });

  it('shows no port label for a single output', () => {
    renderCard({
      node: createMockGraphNode({
        nodeId: 'one',
        category: 'PROCESSOR',
        outputPorts: ['default'],
      }),
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
      node: createMockGraphNode({
        nodeId: 'p1',
        category: 'PROCESSOR',
        outputPorts: [],
      }),
      status: 'pending',
    });

    expect(
      screen.getByTestId('workflow-node-p1').querySelectorAll('.source')
    ).toHaveLength(1);
  });

  it('shows a progress ring, not text, while running', () => {
    renderCard({
      node: createMockGraphNode({
        nodeId: 'vectorize-batch',
        category: 'PROCESSOR',
      }),
      status: 'running',
      task: createMockTask({ status: 'RUNNING', metadata: { progress: 42 } }),
    });

    expect(
      screen
        .getByTestId('progress-ring')
        .style.getPropertyValue('--node-progress')
    ).toBe('42%');
    expect(
      screen.getByTestId('workflow-node-vectorize-batch')
    ).not.toHaveTextContent('42%');
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
      node: createMockGraphNode({
        nodeId: 'sink-2',
        category: 'TERMINAL',
        outputPorts: ['default'],
      }),
      status: 'pending',
    });

    expect(
      screen.getByTestId('workflow-node-sink-2').querySelectorAll('.source')
    ).toHaveLength(0);
  });

  it('uses a plugin-specific icon and shows the plugin name only when the id differs', () => {
    renderCard({
      node: createMockGraphNode({
        nodeId: 'my-step',
        type: 'HTTP_REQUEST',
        category: 'PROCESSOR',
      }),
      status: 'pending',
    });

    expect(screen.getByTestId('node-icon')).toHaveTextContent('http');
    expect(screen.getByTestId('workflow-node-my-step')).toHaveTextContent(
      'Http Request'
    );
  });

  it('titles the card with the node id and subtitles it with the plugin type', () => {
    renderCard({
      node: createMockGraphNode({
        nodeId: 'HTTP_REQUEST-1',
        type: 'HTTP_REQUEST',
        category: 'PROCESSOR',
      }),
      status: 'pending',
    });

    const card = screen.getByTestId('workflow-node-HTTP_REQUEST-1');
    expect(card.querySelectorAll('p')[0]).toHaveTextContent('HTTP_REQUEST-1');
    expect(card.querySelectorAll('p')[1]).toHaveTextContent('Http Request');
  });

  it('renders percent from the percent metadata key', () => {
    renderCard({
      node: createMockGraphNode({ nodeId: 'p3', category: 'PROCESSOR' }),
      status: 'running',
      task: createMockTask({ status: 'RUNNING', metadata: { percent: 66.4 } }),
    });

    expect(
      screen
        .getByTestId('progress-ring')
        .style.getPropertyValue('--node-progress')
    ).toBe('66.4%');
  });

  it('marks failed nodes with a cross badge and a red border', () => {
    renderCard({
      node: createMockGraphNode({ nodeId: 'p4', category: 'PROCESSOR' }),
      status: 'failed',
      task: createMockTask({ status: 'FAILURE' }),
    });

    expect(screen.getByTestId('status-badge')).toHaveTextContent('close');
    expect(screen.getByTestId('workflow-node-p4')).toHaveAttribute(
      'aria-label',
      'p4: Failed'
    );
    expect(screen.getByTestId('workflow-node-p4')).toHaveClass('border-error');
  });

  it('uses a generic icon for nodes with unknown plugin category', () => {
    renderCard({
      node: createMockGraphNode({ nodeId: 'mystery', category: null }),
      status: 'pending',
    });

    expect(screen.getByTestId('node-icon')).toHaveTextContent('extension');
    expect(screen.getByTestId('workflow-node-mystery')).toHaveAttribute(
      'aria-label',
      'mystery: Waiting'
    );
  });

  it('is keyboard focusable', () => {
    renderCard({ node: createMockGraphNode(), status: 'pending' });
    expect(screen.getByTestId('workflow-node-data-ingress')).toHaveAttribute(
      'tabindex',
      '0'
    );
  });

  it('marks a selected card and reads progress from the percent metric', () => {
    renderCard(
      {
        node: createMockGraphNode(),
        status: 'running',
        task: createMockTask({
          status: 'RUNNING',
          metadata: { percent: 61.4 },
        }),
      },
      true
    );

    const card = screen.getByTestId('workflow-node-data-ingress');
    expect(card).toHaveAttribute('data-selected', 'true');
    expect(
      screen
        .getByTestId('progress-ring')
        .style.getPropertyValue('--node-progress')
    ).toBe('61.4%');
  });

  it('shows no progress for a running node without a task', () => {
    renderCard({ node: createMockGraphNode(), status: 'running' });

    expect(
      screen.getByTestId('workflow-node-data-ingress')
    ).not.toHaveTextContent('%');
  });

  describe('description', () => {
    const LONG =
      'Runs the whole repair loop: it reads the failing check, patches the code, and runs the check again until it passes.';

    it('keeps the description out of the card and opens it from the info button', async () => {
      renderCard({
        node: createMockGraphNode({ nodeId: 'repair', description: LONG }),
        status: 'pending',
      });
      expect(screen.queryByText(LONG)).not.toBeInTheDocument();

      await userEvent.click(
        screen.getByRole('button', {
          name: 'Show the description of repair',
        })
      );
      expect(await screen.findByText(LONG)).toHaveClass('whitespace-pre-line');
    });

    it('keeps the open description on screen when it is clicked', async () => {
      renderCard({
        node: createMockGraphNode({ nodeId: 'repair', description: LONG }),
        status: 'pending',
      });
      await userEvent.click(
        screen.getByRole('button', {
          name: 'Show the description of repair',
        })
      );
      await userEvent.click(await screen.findByText(LONG));
      expect(screen.getByText(LONG)).toBeInTheDocument();
    });

    it('has no info button without a description', () => {
      renderCard({
        node: createMockGraphNode({ description: '' }),
        status: 'pending',
      });
      expect(
        screen.queryByRole('button', { name: /description/ })
      ).not.toBeInTheDocument();
    });

    it('keeps the sub-workflow link next to the info button, clear of five ports', () => {
      renderCard({
        node: createMockGraphNode({
          nodeId: 'sub',
          subWorkflowId: 'fix',
          outputPorts: ['a', 'b', 'c', 'd', 'e'],
          description: LONG,
        }),
        status: 'running',
        task: createMockTask({ nodeId: 'sub', status: 'RUNNING' }),
      });
      const card = screen.getByTestId('workflow-node-sub');
      const link = within(card).getByRole('link', {
        name: 'Open sub-workflow fix',
      });
      expect(link).toHaveAttribute('href', '/sessions/s/workflow/fix');
    });
  });
});
