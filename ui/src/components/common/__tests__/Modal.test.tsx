// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { InlineError } from '../InlineError';
import { Modal } from '../Modal';

describe('Modal', () => {
  it('renders an accessible dialog and closes on Escape only', async () => {
    const onClose = vi.fn();
    render(
      <Modal title="Run it" onClose={onClose}>
        <p>body</p>
      </Modal>
    );
    expect(screen.getByRole('dialog', { name: 'Run it' })).toBeInTheDocument();
    expect(screen.getByText('body')).toBeInTheDocument();

    await userEvent.keyboard('a');
    expect(onClose).not.toHaveBeenCalled();
    await userEvent.keyboard('{Escape}');
    expect(onClose).toHaveBeenCalledTimes(1);
  });
});

describe('InlineError', () => {
  it('shows an alert for a message and nothing for null', () => {
    const { rerender } = render(<InlineError message="boom" />);
    expect(screen.getByRole('alert')).toHaveTextContent('boom');
    rerender(<InlineError message={null} />);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});
