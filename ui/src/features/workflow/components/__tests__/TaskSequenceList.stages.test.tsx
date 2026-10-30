// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { TaskSequenceList } from '../TaskSequenceList';
import {
  createMockTask,
  createStagedGraph,
} from '@/test/factories/workflowFactory';

const ids = (element: HTMLElement) =>
  within(element)
    .getAllByTestId(/^task-item-/)
    .map((item) => item.dataset.testid?.replace('task-item-', ''));

describe('TaskSequenceList stages', () => {
  it('lists each stage under one heading with its status, open when it failed', async () => {
    const user = userEvent.setup();
    render(
      <TaskSequenceList
        graph={createStagedGraph()}
        tasksByNode={{
          'lint.fail': createMockTask({
            nodeId: 'lint.fail',
            status: 'FAILURE',
          }),
          unit: createMockTask({ nodeId: 'unit', status: 'SUCCESS' }),
          e2e: createMockTask({ nodeId: 'e2e', status: 'SUCCESS' }),
        }}
      />
    );

    const lint = screen.getByTestId('task-stage-lint');
    const test = screen.getByTestId('task-stage-test');
    expect(ids(lint)).toEqual(['lint', 'lint.fail', 'lint.fix']);
    expect(ids(test)).toEqual(['unit', 'e2e']);
    expect(within(lint).getByTestId('stage-status')).toHaveTextContent(
      'Failed'
    );
    expect(within(test).getByTestId('stage-status')).toHaveTextContent(
      'Succeeded'
    );
    expect(lint.querySelector('details')).toHaveAttribute('open');
    expect(test.querySelector('details')).not.toHaveAttribute('open');
    // lone nodes stay rows of their own
    expect(
      screen
        .getByTestId('task-item-start')
        .closest('[data-testid^="task-stage-"]')
    ).toBeNull();

    // a filter opens every stage and counts what it shows
    await user.type(screen.getByLabelText('Filter nodes'), 'e2e');
    const filtered = screen.getByTestId('task-stage-test');
    expect(filtered.querySelector('details')).toHaveAttribute('open');
    expect(filtered).toHaveTextContent('1 of 2');
    expect(screen.queryByTestId('task-stage-lint')).not.toBeInTheDocument();
  });
});
