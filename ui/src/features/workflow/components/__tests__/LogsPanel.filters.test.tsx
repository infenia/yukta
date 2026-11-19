// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { LogsPanel } from '../LogsPanel';
import { createMockLogEntry } from '@/test/factories/workflowFactory';
import { useJsdomViewport } from '@/test/jsdomViewport';

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
  useJsdomViewport();

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

  it('narrows to the node picked until All nodes is picked again', async () => {
    const user = userEvent.setup();
    const onNode = vi.fn();
    const { rerender } = render(
      <LogsPanel
        entries={entries}
        isStreaming={false}
        node="lint"
        nodes={['build', 'lint']}
        onNode={onNode}
      />
    );
    expect(shown()).toEqual(['lint failed']);
    const picker = screen.getByRole('combobox', {
      name: 'Show lines of node',
    });
    expect(picker).toHaveValue('lint');
    await user.selectOptions(picker, 'All nodes');
    expect(onNode).toHaveBeenLastCalledWith(null);
    await user.selectOptions(picker, 'build');
    expect(onNode).toHaveBeenLastCalledWith('build');
    rerender(
      <LogsPanel
        entries={entries}
        isStreaming={false}
        node={null}
        nodes={['build', 'lint']}
        onNode={onNode}
      />
    );
    expect(shown()).toHaveLength(6);
    expect(picker).toHaveValue('');
  });

  it('has no node picker without nodes to pick', () => {
    render(
      <LogsPanel entries={entries} isStreaming={false} onNode={vi.fn()} />
    );
    expect(
      screen.queryByRole('combobox', { name: 'Show lines of node' })
    ).not.toBeInTheDocument();
  });

  it('leaves the raw stream to the text filter', async () => {
    const user = userEvent.setup();
    render(
      <LogsPanel
        entries={entries}
        isStreaming={false}
        node="lint"
        nodes={['lint']}
        onNode={vi.fn()}
        executionId="e1"
      />
    );
    await user.click(screen.getByRole('button', { name: 'More log options' }));
    await user.click(
      await screen.findByRole('menuitemcheckbox', {
        name: "The server's raw lines",
      })
    );
    expect(await screen.findByText('raw line')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'warn' })).toBeDisabled();
    expect(
      screen.getByRole('combobox', { name: 'Show lines of node' })
    ).toBeDisabled();
  });
});
