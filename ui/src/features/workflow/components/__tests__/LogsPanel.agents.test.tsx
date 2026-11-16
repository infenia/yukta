// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { afterEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { LogsPanel } from '../LogsPanel';

const agents = (waiting: number) => ({
  waiting,
  content: <p>agents here</p>,
});

describe('LogsPanel Agents tab', () => {
  afterEach(() => {
    localStorage.clear();
    vi.restoreAllMocks();
  });

  it('adds an Agents tab with a count of agents that wait, and keeps the choice', async () => {
    const { unmount } = render(
      <LogsPanel entries={[]} isStreaming={false} agents={agents(2)} />
    );
    const tab = screen.getByRole('tab', { name: /Agents/ });
    expect(tab).toHaveTextContent('2 waiting');
    expect(screen.queryByText('agents here')).not.toBeInTheDocument();

    await userEvent.click(tab);

    expect(screen.getByText('agents here')).toBeInTheDocument();
    expect(screen.queryByTestId('logs-body')).not.toBeInTheDocument();
    expect(localStorage.getItem('yukta.dockTab')).toBe('agents');
    unmount();

    // the next visit opens on the tab that was left
    render(<LogsPanel entries={[]} isStreaming={false} agents={agents(0)} />);
    expect(screen.getByText('agents here')).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Agents' })).toHaveAttribute(
      'aria-selected',
      'true'
    );
  });

  it('goes back to the logs', async () => {
    localStorage.setItem('yukta.dockTab', 'agents');
    render(<LogsPanel entries={[]} isStreaming={false} agents={agents(0)} />);

    await userEvent.click(screen.getByRole('tab', { name: 'Logs' }));

    expect(screen.getByTestId('logs-body')).toBeInTheDocument();
    expect(screen.queryByText('agents here')).not.toBeInTheDocument();
  });

  it('works without browser storage', async () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation((key) => {
      if (key === 'yukta.dockTab') throw new Error('blocked');
      return null;
    });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation((key) => {
      if (key === 'yukta.dockTab') throw new Error('blocked');
    });
    render(<LogsPanel entries={[]} isStreaming={false} agents={agents(0)} />);

    await userEvent.click(screen.getByRole('tab', { name: 'Agents' }));

    expect(screen.getByText('agents here')).toBeInTheDocument();
  });

  it('has no tabs of its own when there is nothing to switch to', () => {
    render(<LogsPanel entries={[]} isStreaming={false} />);
    expect(screen.queryByRole('tablist')).not.toBeInTheDocument();
  });

  it('shows the Agents tab when asked to, once per ask', async () => {
    const dock = (nonce: number) => (
      <LogsPanel
        entries={[]}
        isStreaming={false}
        agents={{ ...agents(0), focus: nonce ? { id: 'c1', nonce } : null }}
      />
    );
    const { rerender } = render(dock(0));
    expect(screen.queryByText('agents here')).not.toBeInTheDocument();

    rerender(dock(1));
    expect(screen.getByText('agents here')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('tab', { name: 'Logs' }));
    rerender(dock(1));
    expect(screen.queryByText('agents here')).not.toBeInTheDocument();

    rerender(dock(2));
    expect(screen.getByText('agents here')).toBeInTheDocument();
  });
});
