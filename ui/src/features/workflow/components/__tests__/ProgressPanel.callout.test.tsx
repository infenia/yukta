// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { ProgressPanel } from '../ProgressPanel';
import { SubWorkflowLinks } from '../../hooks/useSubWorkflowLinks';
import {
  createMockGraph,
  createMockTask,
  createStagedGraph,
} from '@/test/factories/workflowFactory';
import type { TaskProgress, WorkflowGraph } from '../../types/workflow';

vi.mock('../ExecutionResultBlock', () => ({
  ExecutionResultBlock: () => null,
}));

function panel(
  graph: WorkflowGraph,
  tasksByNode: Record<string, TaskProgress>,
  links = new Map<string, string>()
) {
  return render(
    <MemoryRouter>
      <SubWorkflowLinks value={links}>
        <ProgressPanel
          graph={graph}
          progress={null}
          tasksByNode={tasksByNode}
          sessionId="s1"
          onClose={vi.fn()}
        />
      </SubWorkflowLinks>
    </MemoryRouter>
  );
}

describe('the failure callout', () => {
  it('is absent while nothing has failed', () => {
    panel(createStagedGraph(), {
      lint: createMockTask({ nodeId: 'lint', status: 'RUNNING' }),
    });
    expect(screen.queryByText(/failed\./)).not.toBeInTheDocument();
  });

  it('names the first failed node in run order, its reason’s first line, and how many more failed', () => {
    const long = 'x'.repeat(300);
    panel(createStagedGraph(), {
      e2e: createMockTask({
        nodeId: 'e2e',
        status: 'FAILURE',
        metadata: { error: 'later' },
      }),
      'lint.fail': createMockTask({
        nodeId: 'lint.fail',
        status: 'TIMED_OUT',
        metadata: { error: `  ${long}\nstack line` },
      }),
    });
    expect(screen.getByText('lint.fail failed.')).toBeInTheDocument();
    expect(screen.getByText(`${'x'.repeat(240)}…`)).toBeInTheDocument();
    expect(screen.getByText('1 more failed.')).toBeInTheDocument();
    expect(
      screen.queryByRole('link', { name: 'Open sub-workflow run' })
    ).not.toBeInTheDocument();
  });

  it('opens the run of a failed sub-workflow node, and follows declaration order without a topological one', () => {
    const graph = { ...createStagedGraph(), topologicalOrder: [] };
    panel(
      graph,
      {
        'lint.fix': createMockTask({
          nodeId: 'lint.fix',
          status: 'FAILURE',
          metadata: { error: 42 },
        }),
      },
      new Map([['lint.fix', '/sessions/s1/workflow/fix?exec=c1']])
    );
    expect(screen.getByText('lint.fix failed.')).toBeInTheDocument();
    expect(
      screen.getByRole('link', { name: 'Open sub-workflow run' })
    ).toHaveAttribute('href', '/sessions/s1/workflow/fix?exec=c1');
  });

  it('shows a short reason as it is', () => {
    panel(createMockGraph(), {
      'vectorize-batch': createMockTask({
        nodeId: 'vectorize-batch',
        status: 'ERROR',
        metadata: { error: 'disk full' },
      }),
    });
    expect(screen.getByText('disk full')).toBeInTheDocument();
  });

  it('has nothing to explore on the Result tab before a run', () => {
    panel(createMockGraph(), {});
    fireEvent.click(screen.getByRole('tab', { name: 'Result' }));
    expect(screen.getByText(/explored once the run ends/)).toBeInTheDocument();
  });
});
