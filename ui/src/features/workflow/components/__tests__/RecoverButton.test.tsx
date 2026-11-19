// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactNode } from 'react';
import { RecoverButton } from '../RecoverButton';

const mutate = vi.fn();
const recoverState: { error: Error | null } = { error: null };

vi.mock('@/features/supervisors/components/RequireControl', () => ({
  RequireControl: ({ children }: { children: ReactNode }) => <>{children}</>,
}));

vi.mock('@/components/ui/tooltip', () => ({
  Tip: ({ children }: { children: ReactNode }) => <>{children}</>,
}));

vi.mock('../../hooks/useExecutionControl', () => ({
  controlErrorMessage: () => 'failed',
  useExecutionControl: () => ({
    recover: { isPending: false, error: recoverState.error, mutate },
  }),
}));

const props = {
  sessionId: 's1',
  workflowId: 'w1',
  executionId: 'e1',
  onRecovered: vi.fn(),
};

describe('RecoverButton', () => {
  it('shows the label with the given size when not icon only', () => {
    render(<RecoverButton {...props} size="sm" />);
    const button = screen.getByRole('button', { name: 'Recover' });
    expect(button).toHaveTextContent('Recover');
  });

  it('renders an icon button with an accessible name when icon only', () => {
    render(<RecoverButton {...props} iconOnly />);
    const button = screen.getByRole('button', { name: 'Recover' });
    expect(button).not.toHaveTextContent('Recover');
  });

  it('uses the given class name instead of the generated one', () => {
    render(<RecoverButton {...props} iconOnly className="custom-seg" />);
    expect(screen.getByRole('button', { name: 'Recover' })).toHaveClass(
      'custom-seg'
    );
  });

  it('shows the control error when recovering fails', () => {
    recoverState.error = new Error('boom');
    try {
      render(<RecoverButton {...props} />);
      expect(screen.getByText('failed')).toBeInTheDocument();
    } finally {
      recoverState.error = null;
    }
  });

  it('does nothing when the confirmation is cancelled', async () => {
    const user = userEvent.setup();
    mutate.mockClear();
    render(<RecoverButton {...props} />);
    await user.click(screen.getByRole('button', { name: 'Recover' }));
    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(mutate).not.toHaveBeenCalled();
    expect(screen.queryByRole('button', { name: 'Cancel' })).toBeNull();
  });

  it('closes the confirmation from the dialog close button', async () => {
    const user = userEvent.setup();
    mutate.mockClear();
    render(<RecoverButton {...props} />);
    await user.click(screen.getByRole('button', { name: 'Recover' }));
    await user.click(screen.getByRole('button', { name: 'Close dialog' }));
    expect(mutate).not.toHaveBeenCalled();
    expect(screen.queryByRole('button', { name: 'Cancel' })).toBeNull();
  });

  it('recovers after confirmation and reports the new run id', async () => {
    const user = userEvent.setup();
    const onRecovered = vi.fn();
    mutate.mockImplementation(
      (
        _id: string,
        options: { onSuccess: (r: { executionId: string }) => void }
      ) => options.onSuccess({ executionId: 'e2' })
    );
    render(<RecoverButton {...props} onRecovered={onRecovered} />);
    await user.click(screen.getByRole('button', { name: 'Recover' }));
    const [confirm] = screen
      .getAllByRole('button', { name: 'Recover' })
      .slice(-1);
    await user.click(confirm);
    expect(mutate).toHaveBeenCalledWith('e1', expect.any(Object));
    expect(onRecovered).toHaveBeenCalledWith('e2');
  });
});
