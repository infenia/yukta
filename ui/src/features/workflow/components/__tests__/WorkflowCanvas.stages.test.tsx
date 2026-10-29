// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { WorkflowCanvas } from '../WorkflowCanvas';
import {
  createMockTask,
  createStagedGraph,
} from '@/test/factories/workflowFactory';

describe('WorkflowCanvas stages', () => {
  it('shows a closed stage as a box, an open one framed, and toggles either', async () => {
    const user = userEvent.setup();
    const onToggle = vi.fn();
    render(
      <WorkflowCanvas
        graph={createStagedGraph()}
        tasksByNode={{
          'lint.fail': createMockTask({
            nodeId: 'lint.fail',
            status: 'FAILURE',
          }),
          unit: createMockTask({ nodeId: 'unit', status: 'RUNNING' }),
          e2e: createMockTask({ nodeId: 'e2e', status: 'FAILURE' }),
        }}
        stageView={{ open: new Set(['lint']), onToggle }}
      />
    );

    const test = screen.getByTestId('stage-test');
    expect(test).toHaveAccessibleName('Stage test: Failed, 2 nodes');
    expect(screen.queryByTestId('workflow-node-unit')).not.toBeInTheDocument();
    expect(screen.getByTestId('stage-frame-lint')).toHaveAttribute(
      'data-status',
      'failed'
    );
    expect(screen.getByTestId('workflow-node-lint.fail')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Expand stage test' }));
    await user.click(
      screen.getByRole('button', { name: 'Collapse stage lint' })
    );
    expect(onToggle.mock.calls).toEqual([['test'], ['lint']]);
  });
});
