// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { TooltipProvider } from '@/components/ui/tooltip';
import { ToolCard } from '../components/ToolCard';

const call = { id: 'c1', name: 'list_sessions', args: { limit: 5 } };
const result = (text: string, isError = false) => ({
  role: 'tool' as const,
  id: 'c1',
  name: 'list_sessions',
  result: text,
  isError,
});

const show = (
  props: Partial<Parameters<typeof ToolCard>[0]> = {},
  onApprove = vi.fn()
) =>
  render(
    <TooltipProvider>
      <ToolCard call={call} awaiting={false} onApprove={onApprove} {...props} />
    </TooltipProvider>
  );

describe('ToolCard', () => {
  it('shows a call that is still running, closed', () => {
    show();

    expect(screen.getByText('list_sessions')).toBeInTheDocument();
    expect(screen.getByText('Running')).toBeInTheDocument();
    expect(screen.queryByText('Approve')).not.toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: /list_sessions/ })
    ).toHaveAttribute('aria-expanded', 'false');
  });

  it('opens to the arguments and the result, JSON indented, and closes again', async () => {
    const { container } = show({ result: result('{"a":1}') });
    const toggle = screen.getByRole('button', { name: /list_sessions/ });
    expect(screen.getByText('Done')).toBeInTheDocument();

    await userEvent.click(toggle);

    expect(toggle).toHaveAttribute('aria-expanded', 'true');
    expect(container).toHaveTextContent('"limit": 5');
    expect(container).toHaveTextContent('"a": 1');

    await userEvent.click(toggle);
    expect(container).not.toHaveTextContent('"limit": 5');
  });

  it('shows a result that is not JSON as it is, and a failure as failed', async () => {
    show({ result: result('plain words', true) });

    expect(screen.getByText('Failed')).toBeInTheDocument();
    await userEvent.click(
      screen.getByRole('button', { name: /list_sessions/ })
    );
    expect(screen.getByText('plain words')).toBeInTheDocument();
  });

  it('asks for approval, open, and reports the answer', async () => {
    const onApprove = vi.fn();
    const { container } = show({ awaiting: true }, onApprove);

    expect(screen.getByText('Needs approval')).toBeInTheDocument();
    expect(container).toHaveTextContent('"limit": 5');

    await userEvent.click(screen.getByRole('button', { name: 'Approve' }));
    await userEvent.click(screen.getByRole('button', { name: 'Deny' }));

    expect(onApprove.mock.calls).toEqual([[true], [false]]);
  });
  it('says how long a call it saw running took, but not one that arrived finished', () => {
    vi.useFakeTimers();
    const view = show();
    vi.advanceTimersByTime(1500);
    view.rerender(
      <TooltipProvider>
        <ToolCard
          call={call}
          result={result('{}')}
          awaiting={false}
          onApprove={vi.fn()}
        />
      </TooltipProvider>
    );
    expect(screen.getByText(/Done · 1\.5 s/)).toBeInTheDocument();
    vi.useRealTimers();
    view.unmount();

    show({ result: result('{}') });
    expect(screen.getByText('Done')).toBeInTheDocument();
  });

  it('shows the CLI command for a call the CLI has one for', async () => {
    show({
      call: {
        id: 'c1',
        name: 'start_workflow',
        args: { sessionId: 's', workflowId: 'w' },
      },
      awaiting: true,
    });

    expect(
      screen.getByRole('button', { name: 'Copy same in the CLI' })
    ).toBeInTheDocument();
  });
});
