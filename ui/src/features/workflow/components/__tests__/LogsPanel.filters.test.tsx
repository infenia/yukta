// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { LogsPanel } from '../LogsPanel';
import { createMockLogEntry } from '@/test/factories/workflowFactory';

vi.mock('../../hooks/useRawLogLines', () => ({
  useRawLogLines: () => ['raw line'],
}));

const entries = [
  createMockLogEntry({ message: 'debug detail', level: 'DEBUG' }),
  createMockLogEntry({ message: 'plain info', level: 'INFO' }),
  createMockLogEntry({ message: 'unlevelled', level: null }),
  createMockLogEntry({ message: 'odd level', level: 'NOTICE' }),
  createMockLogEntry({ message: 'disk nearly full', level: 'WARN' }),
  createMockLogEntry({
    message: 'lint failed',
    level: 'ERROR',
    pluginId: 'lint',
  }),
];

const shown = () =>
  [
    'debug detail',
    'plain info',
    'unlevelled',
    'odd level',
    'disk nearly full',
    'lint failed',
  ].filter((message) => screen.queryByText(message));

describe('LogsPanel level and node filters', () => {
  it('shows only lines at or above the chosen level', async () => {
    const user = userEvent.setup();
    render(<LogsPanel entries={entries} isStreaming={false} />);
    expect(shown()).toHaveLength(6);
    await user.click(screen.getByRole('button', { name: 'info' }));
    expect(shown()).toEqual([
      'plain info',
      'unlevelled',
      'odd level',
      'disk nearly full',
      'lint failed',
    ]);
    await user.click(screen.getByRole('button', { name: 'warn' }));
    expect(shown()).toEqual(['disk nearly full', 'lint failed']);
    await user.click(screen.getByRole('button', { name: 'error' }));
    expect(shown()).toEqual(['lint failed']);
    expect(screen.getByRole('button', { name: 'error' })).toHaveAttribute(
      'aria-pressed',
      'true'
    );
  });

  it('narrows to one node until the chip is cleared', async () => {
    const user = userEvent.setup();
    const onClearNode = vi.fn();
    const { rerender } = render(
      <LogsPanel
        entries={entries}
        isStreaming={false}
        node="lint"
        onClearNode={onClearNode}
      />
    );
    expect(shown()).toEqual(['lint failed']);
    expect(screen.getByText('Node: lint')).toBeInTheDocument();
    await user.click(
      screen.getByRole('button', {
        name: "Show every node's lines, not only lint's",
      })
    );
    expect(onClearNode).toHaveBeenCalled();
    rerender(<LogsPanel entries={entries} isStreaming={false} node={null} />);
    expect(shown()).toHaveLength(6);
  });

  it('leaves the raw stream to the text filter', async () => {
    const user = userEvent.setup();
    render(
      <LogsPanel
        entries={entries}
        isStreaming={false}
        node="lint"
        executionId="e1"
      />
    );
    await user.click(screen.getByRole('button', { name: 'RAW' }));
    expect(await screen.findByText('raw line')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'warn' })).toBeDisabled();
    expect(screen.queryByText('Node: lint')).not.toBeInTheDocument();
  });
});
