// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ExecutionSummaryBlock } from '../ExecutionSummaryBlock';
import { createMockProgress } from '@/test/factories/workflowFactory';

/** Installs a clipboard after user-event has set up its own, so ours is the one used. */
function installClipboard(writeText: (text: string) => Promise<void>) {
  Object.defineProperty(navigator, 'clipboard', {
    configurable: true,
    value: { writeText },
  });
}

describe('ExecutionSummaryBlock', () => {
  it('copies the execution id and confirms in place', async () => {
    const user = userEvent.setup();
    const writeText = vi.fn(() => Promise.resolve());
    installClipboard(writeText);
    render(
      <ExecutionSummaryBlock progress={createMockProgress()} sessionId="s1" />
    );

    await user.click(screen.getByRole('button', { name: 'Copy execution id' }));

    expect(writeText).toHaveBeenCalledWith('exec-091-qp-55');
    await waitFor(() =>
      expect(screen.getByTestId('execution-summary')).toHaveTextContent('check')
    );
  });

  it('copies the session id from the route when there is no run', async () => {
    const user = userEvent.setup();
    const writeText = vi.fn(() => Promise.resolve());
    installClipboard(writeText);
    render(
      <ExecutionSummaryBlock progress={null} sessionId="SESSION_A92_XP_2024" />
    );

    await user.click(screen.getByRole('button', { name: 'Copy session id' }));

    expect(writeText).toHaveBeenCalledWith('SESSION_A92_XP_2024');
  });

  it('shows the failure icon when the clipboard refuses', async () => {
    const user = userEvent.setup();
    installClipboard(() => Promise.reject(new Error('denied')));
    render(
      <ExecutionSummaryBlock progress={createMockProgress()} sessionId="s1" />
    );

    await user.click(screen.getByRole('button', { name: 'Copy execution id' }));

    await waitFor(() =>
      expect(screen.getByTestId('execution-summary')).toHaveTextContent('error')
    );
  });

  it('shows the failure icon when there is no clipboard', async () => {
    const user = userEvent.setup();
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: undefined,
    });
    render(
      <ExecutionSummaryBlock progress={createMockProgress()} sessionId="s1" />
    );

    await user.click(screen.getByRole('button', { name: 'Copy session id' }));

    await waitFor(() =>
      expect(screen.getByTestId('execution-summary')).toHaveTextContent('error')
    );
  });

  it('renders the execution summary rows', () => {
    render(
      <ExecutionSummaryBlock progress={createMockProgress()} sessionId="s1" />
    );

    const block = screen.getByTestId('execution-summary');
    expect(block).toHaveTextContent('Execution summary');
    expect(block).toHaveTextContent('exec-091-qp-55');
    expect(block).toHaveTextContent('SESSION_A92_XP_2024');
    expect(block).toHaveTextContent('Running');
    expect(block).toHaveTextContent('14:22:01');
  });

  it('shows the placeholder end time for a running execution', () => {
    render(
      <ExecutionSummaryBlock progress={createMockProgress()} sessionId="s1" />
    );
    expect(screen.getByTestId('execution-summary')).toHaveTextContent(
      '--:--:--'
    );
  });

  it('renders the never-run state with placeholders and the route session id', () => {
    render(
      <ExecutionSummaryBlock progress={null} sessionId="SESSION_A92_XP_2024" />
    );

    const block = screen.getByTestId('execution-summary');
    expect(block).toHaveTextContent('—');
    expect(block).toHaveTextContent('SESSION_A92_XP_2024');
    expect(block).toHaveTextContent('Not run');
  });

  it('renders the end time once the execution finishes', () => {
    render(
      <ExecutionSummaryBlock
        progress={createMockProgress({
          status: 'COMPLETED',
          endTime: '2026-07-26T14:25:33',
        })}
        sessionId="s1"
      />
    );
    expect(screen.getByTestId('execution-summary')).toHaveTextContent(
      '14:25:33'
    );
  });
});
