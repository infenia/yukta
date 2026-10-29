// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { BreakpointDialog } from '../BreakpointDialog';

function setup(props: { pending?: boolean; error?: string | null } = {}) {
  const onSubmit = vi.fn();
  const onClose = vi.fn();
  render(
    <BreakpointDialog
      nodeId="n1"
      pending={props.pending ?? false}
      error={props.error ?? null}
      onSubmit={onSubmit}
      onClose={onClose}
    />
  );
  return { onSubmit, onClose, user: userEvent.setup() };
}

describe('BreakpointDialog', () => {
  it('needs both the field and the value', async () => {
    const { onSubmit, user } = setup();
    await user.click(screen.getByRole('button', { name: 'Set' }));
    expect(screen.getByRole('alert')).toHaveTextContent(
      'Both the field and the value are required'
    );

    await user.type(screen.getByLabelText('Field'), 'payload.status');
    await user.click(screen.getByRole('button', { name: 'Set' }));
    expect(onSubmit).not.toHaveBeenCalled();

    await user.type(screen.getByLabelText('Equals'), '   ');
    await user.click(screen.getByRole('button', { name: 'Set' }));
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('sends the trimmed field and value and clears the complaint', async () => {
    const { onSubmit, user } = setup();
    await user.click(screen.getByRole('button', { name: 'Set' }));
    await user.type(screen.getByLabelText('Field'), ' payload.status ');
    await user.type(screen.getByLabelText('Equals'), ' error ');
    await user.click(screen.getByRole('button', { name: 'Set' }));
    expect(onSubmit).toHaveBeenCalledWith('payload.status', 'error');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('shows the server error, disables Set while pending, and closes on Cancel', async () => {
    const { onClose, user } = setup({ error: 'node unknown', pending: true });
    expect(screen.getByRole('alert')).toHaveTextContent('node unknown');
    expect(screen.getByRole('button', { name: 'Set' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(onClose).toHaveBeenCalled();
  });
});
