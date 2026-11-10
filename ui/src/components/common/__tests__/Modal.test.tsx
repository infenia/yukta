// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { InlineError } from '../InlineError';
import { Modal } from '../Modal';

describe('Modal', () => {
  it('renders an accessible dialog and closes on Escape', async () => {
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

  it('closes from its close button', async () => {
    const onClose = vi.fn();
    render(
      <Modal title="Run it" onClose={onClose}>
        <p>body</p>
      </Modal>
    );
    await userEvent.click(screen.getByRole('button', { name: 'Close dialog' }));
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('closes on a click on the backdrop, but not on a click inside the dialog', async () => {
    const onClose = vi.fn();
    render(
      <Modal title="Run it" onClose={onClose}>
        <p>body</p>
      </Modal>
    );
    await userEvent.click(screen.getByText('body'));
    expect(onClose).not.toHaveBeenCalled();
    await userEvent.click(
      screen.getByRole('dialog').parentElement as HTMLElement
    );
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('stays open when a press starts inside the dialog and ends on the backdrop', () => {
    const onClose = vi.fn();
    render(
      <Modal title="Run it" onClose={onClose}>
        <input aria-label="field" />
      </Modal>
    );
    const backdrop = screen.getByRole('dialog').parentElement as HTMLElement;
    fireEvent.mouseDown(screen.getByLabelText('field'));
    fireEvent.click(backdrop);
    expect(onClose).not.toHaveBeenCalled();
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
