// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { RunHistoryDropdown } from '../RunHistoryDropdown';
import { createMockExecution } from '@/test/factories/workflowFactory';

const executions = [
  createMockExecution(),
  createMockExecution({ executionId: 'exec-old-1', status: 'SUCCESS' }),
];

describe('RunHistoryDropdown', () => {
  it('is disabled and reads "Not Yet Run" when there are no runs', () => {
    render(
      <RunHistoryDropdown
        executions={[]}
        selectedExecutionId={null}
        onSelect={vi.fn()}
      />
    );
    const trigger = screen.getByRole('button', { name: /run history/i });
    expect(trigger).toBeDisabled();
    expect(trigger).toHaveTextContent('Not Yet Run');
  });

  it('shows the full run id and no "started" text in the trigger', () => {
    render(
      <RunHistoryDropdown
        executions={executions}
        selectedExecutionId="exec-091-qp-55"
        onSelect={vi.fn()}
      />
    );
    const trigger = screen.getByRole('button', { name: /run history/i });
    expect(trigger).toHaveTextContent('exec-091-qp-55');
    expect(trigger).not.toHaveTextContent(/started/i);
  });

  it('shows the run status as an icon only, labelled for assistive tech', () => {
    render(
      <RunHistoryDropdown
        executions={[createMockExecution({ status: 'SUCCESS' })]}
        selectedExecutionId={createMockExecution().executionId}
        onSelect={vi.fn()}
      />
    );
    const status = within(
      screen.getByRole('button', { name: /run history/i })
    ).getByTestId('run-status');
    expect(status).toHaveAttribute('aria-label', 'Succeeded');
    expect(status).not.toHaveTextContent('Succeeded');
  });

  it('lists the runs in the same order, with the same id as the trigger', async () => {
    const user = userEvent.setup();
    render(
      <RunHistoryDropdown
        executions={executions}
        selectedExecutionId="exec-old-1"
        onSelect={vi.fn()}
      />
    );
    await user.click(screen.getByRole('button', { name: /run history/i }));
    const rows = within(await screen.findByRole('menu')).getAllByRole(
      'menuitem'
    );
    expect(rows[0]).toHaveTextContent(executions[0].executionId);
    expect(rows[1]).toHaveTextContent('exec-old-1');
    expect(rows[1]).toHaveAttribute('aria-current', 'true');
  });

  it('renders a dash before a selection exists', () => {
    render(
      <RunHistoryDropdown
        executions={executions}
        selectedExecutionId={null}
        onSelect={vi.fn()}
      />
    );
    expect(
      screen.getByRole('button', { name: /run history/i })
    ).toHaveTextContent('—');
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
