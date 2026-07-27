// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { LogsPanel } from '../LogsPanel';
import { createMockLogEntry } from '@/test/factories/workflowFactory';

const entries = [
  createMockLogEntry({ message: 'Initializing workflow engine...', level: 'INFO' }),
  createMockLogEntry({ message: 'High memory pressure detected', level: 'WARN' }),
  createMockLogEntry({ message: 'Connection lost', level: 'ERROR' }),
  createMockLogEntry({ message: 'verbose detail', level: 'DEBUG' }),
  createMockLogEntry({ message: 'no level line', level: null }),
];

describe('LogsPanel', () => {
  it('renders log lines with timestamp, level and plugin id', () => {
    render(<LogsPanel entries={entries.slice(0, 1)} isStreaming={false} />);

    const body = screen.getByTestId('logs-body');
    expect(body).toHaveTextContent('INFO [vectorize-batch] Initializing workflow engine...');
  });

  it('colours WARN and ERROR lines', () => {
    render(<LogsPanel entries={entries} isStreaming={false} />);

    expect(screen.getByText(/High memory pressure/).className).toContain('text-amber-400');
    expect(screen.getByText(/Connection lost/).className).toContain('text-red-400');
    expect(screen.getByText(/verbose detail/).className).toContain('text-neutral-500');
    expect(screen.getByText(/no level line/).className).toContain('text-neutral-200');
  });

  it('shows the STREAMING pill only while streaming', () => {
    const { rerender } = render(<LogsPanel entries={entries} isStreaming />);
    expect(screen.getByTestId('streaming-pill')).toBeInTheDocument();

    rerender(<LogsPanel entries={entries} isStreaming={false} />);
    expect(screen.queryByTestId('streaming-pill')).toBeNull();
  });

  it('filters lines by substring, case-insensitively', async () => {
    const user = userEvent.setup();
    render(<LogsPanel entries={entries} isStreaming={false} />);

    await user.type(screen.getByLabelText('Filter logs'), 'MEMORY');

    const body = screen.getByTestId('logs-body');
    expect(body).toHaveTextContent('High memory pressure');
    expect(body).not.toHaveTextContent('Connection lost');
  });

  it('shows the empty state when nothing matches', async () => {
    const user = userEvent.setup();
    render(<LogsPanel entries={entries} isStreaming={false} />);

    await user.type(screen.getByLabelText('Filter logs'), 'zzz-no-match');
    expect(screen.getByTestId('logs-body')).toHaveTextContent('No log output.');
  });

  it('collapses and expands the terminal body', async () => {
    const user = userEvent.setup();
    render(<LogsPanel entries={entries} isStreaming={false} />);

    await user.click(screen.getByRole('button', { name: /collapse execution logs/i }));
    expect(screen.queryByTestId('logs-body')).toBeNull();

    await user.click(screen.getByRole('button', { name: /expand execution logs/i }));
    expect(screen.getByTestId('logs-body')).toBeInTheDocument();
  });

  it('toggles auto-scroll', async () => {
    const user = userEvent.setup();
    render(<LogsPanel entries={entries} isStreaming={false} />);

    const toggle = screen.getByRole('switch', { name: /toggle auto-scroll/i });
    expect(toggle).toHaveAttribute('aria-checked', 'true');

    await user.click(toggle);
    expect(toggle).toHaveAttribute('aria-checked', 'false');
  });

  it('renders the empty terminal without entries', () => {
    render(<LogsPanel entries={[]} isStreaming={false} />);
    expect(screen.getByTestId('logs-body')).toHaveTextContent('No log output.');
  });
});
