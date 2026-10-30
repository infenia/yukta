// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { afterEach, describe, it, expect, vi } from 'vitest';
import { act, fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { LogsPanel } from '../LogsPanel';
import {
  createMockLogEntry,
  createMockProgress,
  createMockTask,
} from '@/test/factories/workflowFactory';

const entries = [
  createMockLogEntry({
    message: 'Initializing workflow engine...',
    level: 'INFO',
  }),
  createMockLogEntry({
    message: 'High memory pressure detected',
    level: 'WARN',
  }),
  createMockLogEntry({ message: 'Connection lost', level: 'ERROR' }),
  createMockLogEntry({ message: 'verbose detail', level: 'DEBUG' }),
  createMockLogEntry({ message: 'no level line', level: null }),
];

describe('LogsPanel', () => {
  const progress = createMockProgress({
    startTime: '2026-10-30T10:00:00Z',
    endTime: null,
    tasks: [
      createMockTask({
        nodeId: 'fetch',
        status: 'SUCCESS',
        startTime: '2026-10-30T10:00:01Z',
        endTime: '2026-10-30T10:00:02Z',
      }),
      createMockTask({
        nodeId: 'load',
        status: 'RUNNING',
        startTime: '2026-10-30T10:00:03Z',
        endTime: null,
      }),
    ],
  });

  it('has no Events tab without a run', () => {
    render(<LogsPanel entries={entries} isStreaming={false} />);
    expect(
      screen.queryByRole('tab', { name: 'Events' })
    ).not.toBeInTheDocument();
  });

  it("shows the run's status transitions on the Events tab, narrowed to the clicked node", async () => {
    const user = userEvent.setup();
    const { rerender } = render(
      <LogsPanel entries={entries} isStreaming={false} progress={progress} />
    );
    await user.click(screen.getByRole('tab', { name: 'Events' }));

    const list = screen.getByRole('list', { name: 'Run events' });
    expect(
      Array.from(list.querySelectorAll('li')).map((li) =>
        li.textContent?.replace(/^\[[^\]]*\] /, '')
      )
    ).toEqual([
      'run Started',
      'fetch Started',
      'fetch Succeeded',
      'load Started',
    ]);
    // the log-only controls are hidden
    expect(screen.queryByLabelText('Filter logs')).not.toBeInTheDocument();

    rerender(
      <LogsPanel
        entries={entries}
        isStreaming={false}
        progress={progress}
        node="ghost"
      />
    );
    expect(screen.getByText('No events yet.')).toBeInTheDocument();

    await user.click(screen.getByRole('tab', { name: 'Logs' }));
    expect(screen.getByLabelText('Filter logs')).toBeInTheDocument();
    expect(
      screen.queryByRole('list', { name: 'Run events' })
    ).not.toBeInTheDocument();
  });

  it('virtualises long logs so only the visible window is in the DOM', () => {
    const many = Array.from({ length: 2000 }, (_, i) =>
      createMockLogEntry({ message: `line ${i}` })
    );
    render(<LogsPanel entries={many} isStreaming={false} />);

    const rows = screen.getByTestId('logs-body').querySelectorAll('p');
    expect(rows.length).toBeGreaterThan(0);
    expect(rows.length).toBeLessThan(100);
  });

  it('says how many earlier lines are no longer buffered', () => {
    const { rerender } = render(
      <LogsPanel entries={entries} isStreaming={false} />
    );
    expect(screen.queryByTestId('dropped-pill')).not.toBeInTheDocument();

    rerender(
      <LogsPanel entries={entries} isStreaming={false} dropped={1234} />
    );
    expect(screen.getByTestId('dropped-pill')).toHaveTextContent(
      '1,234 EARLIER LINES HIDDEN'
    );
  });

  it('renders log lines with timestamp, level and plugin id', () => {
    render(<LogsPanel entries={entries.slice(0, 1)} isStreaming={false} />);

    const body = screen.getByTestId('logs-body');
    expect(body).toHaveTextContent(
      'INFO [vectorize-batch] Initializing workflow engine...'
    );
  });

  it('colours WARN and ERROR lines', () => {
    render(<LogsPanel entries={entries} isStreaming={false} />);

    expect(screen.getByText(/High memory pressure/).className).toContain(
      'text-log-warn'
    );
    expect(screen.getByText(/Connection lost/).className).toContain(
      'text-log-error'
    );
    expect(screen.getByText(/verbose detail/).className).toContain(
      'text-log-debug'
    );
    expect(screen.getByText(/no level line/).className).toContain(
      'text-terminal-fg'
    );
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

    await user.click(
      screen.getByRole('button', { name: /collapse execution logs/i })
    );
    expect(screen.queryByTestId('logs-body')).toBeNull();

    await user.click(
      screen.getByRole('button', { name: /expand execution logs/i })
    );
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

  describe('copy, wrap and jump to latest', () => {
    const original = Object.getOwnPropertyDescriptor(navigator, 'clipboard');
    afterEach(() => {
      if (original) Object.defineProperty(navigator, 'clipboard', original);
      else Reflect.deleteProperty(navigator, 'clipboard');
    });

    const withClipboard = (writeText: (text: string) => Promise<void>) =>
      Object.defineProperty(navigator, 'clipboard', {
        configurable: true,
        value: { writeText },
      });

    it('copies the visible lines and shows the outcome', async () => {
      const user = userEvent.setup({ delay: null });
      const writeText = vi.fn().mockResolvedValue(undefined);
      withClipboard(writeText);
      render(<LogsPanel entries={entries} isStreaming={false} />);

      await user.click(screen.getByRole('button', { name: 'COPY' }));

      expect(writeText).toHaveBeenCalledOnce();
      expect(await screen.findByText('COPIED')).toBeInTheDocument();
    });

    it('resets the copy label after a moment', async () => {
      vi.useFakeTimers();
      try {
        withClipboard(vi.fn().mockResolvedValue(undefined));
        render(<LogsPanel entries={entries} isStreaming={false} />);

        fireEvent.click(screen.getByRole('button', { name: 'COPY' }));
        await act(async () => {
          await vi.advanceTimersByTimeAsync(1600);
        });

        expect(
          screen.getByRole('button', { name: 'COPY' })
        ).toBeInTheDocument();
      } finally {
        vi.useRealTimers();
      }
    });

    it('reports a failed copy', async () => {
      const user = userEvent.setup({ delay: null });
      withClipboard(vi.fn().mockRejectedValue(new Error('denied')));
      render(<LogsPanel entries={entries} isStreaming={false} />);

      await user.click(screen.getByRole('button', { name: 'COPY' }));

      expect(await screen.findByText('FAILED')).toBeInTheDocument();
    });

    it('reports a failed copy when there is no clipboard', async () => {
      const user = userEvent.setup({ delay: null });
      Object.defineProperty(navigator, 'clipboard', {
        configurable: true,
        value: undefined,
      });
      render(<LogsPanel entries={entries} isStreaming={false} />);

      await user.click(screen.getByRole('button', { name: 'COPY' }));

      expect(await screen.findByText('FAILED')).toBeInTheDocument();
    });

    it('toggles wrapping', async () => {
      const user = userEvent.setup({ delay: null });
      render(<LogsPanel entries={entries} isStreaming={false} />);
      const wrap = screen.getByRole('button', { name: 'WRAP' });
      expect(wrap).toHaveAttribute('aria-pressed', 'false');

      await user.click(wrap);

      expect(wrap).toHaveAttribute('aria-pressed', 'true');
      expect(
        screen.getByText(/Connection lost/).closest('p')?.className
      ).toContain('whitespace-pre-wrap');
    });

    it('jumps back to the latest line after auto-scroll was turned off', async () => {
      const user = userEvent.setup({ delay: null });
      render(<LogsPanel entries={entries} isStreaming={false} />);
      await user.click(
        screen.getByRole('switch', { name: /toggle auto-scroll/i })
      );

      await user.click(screen.getByRole('button', { name: 'Jump to latest' }));

      expect(
        screen.getByRole('switch', { name: /toggle auto-scroll/i })
      ).toHaveAttribute('aria-checked', 'true');
    });
  });
});
