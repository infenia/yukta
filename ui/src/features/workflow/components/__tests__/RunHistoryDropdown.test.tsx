// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { RunHistoryDropdown } from '../RunHistoryDropdown';
import { createMockExecution } from '@/test/factories/workflowFactory';

const executions = [
  createMockExecution(),
  createMockExecution({ executionId: 'exec-old-1', status: 'SUCCESS' }),
];

describe('RunHistoryDropdown', () => {
  it('shows the empty state when there are no runs', () => {
    render(
      <RunHistoryDropdown executions={[]} selectedExecutionId={null} onSelect={vi.fn()} />
    );
    expect(screen.getByTestId('run-history-empty')).toHaveTextContent('No runs yet');
  });

  it('renders the selected execution id in the trigger', () => {
    render(
      <RunHistoryDropdown
        executions={executions}
        selectedExecutionId="exec-091-qp-55"
        onSelect={vi.fn()}
      />
    );
    expect(screen.getByRole('button', { name: /run history/i })).toHaveTextContent(
      'exec-091-qp-55'
    );
  });

  it('renders a dash before a selection exists', () => {
    render(
      <RunHistoryDropdown executions={executions} selectedExecutionId={null} onSelect={vi.fn()} />
    );
    expect(screen.getByRole('button', { name: /run history/i })).toHaveTextContent('—');
  });

  it('lists executions and notifies selection', async () => {
    const user = userEvent.setup();
    const onSelect = vi.fn();
    render(
      <RunHistoryDropdown
        executions={executions}
        selectedExecutionId="exec-091-qp-55"
        onSelect={onSelect}
      />
    );

    await user.click(screen.getByRole('button', { name: /run history/i }));
    await user.click(await screen.findByText('exec-old-1'));

    expect(onSelect).toHaveBeenCalledWith('exec-old-1');
  });
});
