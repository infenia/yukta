// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { WorkflowCanvas } from '../WorkflowCanvas';
import { createMockGraph, createMockProgress } from '@/test/factories/workflowFactory';
import type { TaskProgress } from '../../types/workflow';

function tasksByNode(): Record<string, TaskProgress> {
  return Object.fromEntries(createMockProgress().tasks.map((task) => [task.nodeId, task]));
}

describe('WorkflowCanvas', () => {
  it('renders every workflow node placeholder', () => {
    render(<WorkflowCanvas graph={createMockGraph()} tasksByNode={tasksByNode()} />);

    expect(screen.getByTestId('workflow-canvas')).toBeInTheDocument();
    expect(screen.getByTestId('workflow-node-data-ingress')).toBeInTheDocument();
    expect(screen.getByTestId('workflow-node-vectorize-batch')).toBeInTheDocument();
    expect(screen.getByTestId('workflow-node-anomaly-detection')).toBeInTheDocument();
    expect(screen.getByTestId('workflow-node-sink-storage')).toBeInTheDocument();
  });

  it('reflects task statuses on the node placeholders', () => {
    render(<WorkflowCanvas graph={createMockGraph()} tasksByNode={tasksByNode()} />);

    expect(screen.getByTestId('workflow-node-data-ingress')).toHaveTextContent('Status: Done');
    expect(screen.getByTestId('workflow-node-vectorize-batch')).toHaveTextContent(
      'Status: Active'
    );
    expect(screen.getByTestId('workflow-node-sink-storage')).toHaveTextContent('Status: Waiting');
  });
});
