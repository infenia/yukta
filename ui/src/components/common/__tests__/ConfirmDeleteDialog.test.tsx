// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { QueryClientProvider } from '@tanstack/react-query';
import { createTestQueryClient } from '@/test/utils/testUtils';
import { ApiError } from '@/lib/apiClient';
import { ConfirmDeleteDialog, activeRunIds } from '../ConfirmDeleteDialog';

const BLOCKED =
  '2 run(s) are still active: e1, e2. Stop them first, or delete with force=true to cancel them.';

function setup(remove: (force: boolean) => Promise<unknown>) {
  const onDone = vi.fn();
  const onClose = vi.fn();
  render(
    <QueryClientProvider client={createTestQueryClient()}>
      <MemoryRouter>
        <ConfirmDeleteDialog
          title="Delete session s1"
          id="s1"
          removes="the session"
          keeps="the run history"
          remove={remove}
          runLink={(run) => `/runs?text=${run}`}
          onDone={onDone}
          onClose={onClose}
        />
      </MemoryRouter>
    </QueryClientProvider>
  );
  return { onDone, onClose, user: userEvent.setup() };
}

describe('activeRunIds', () => {
  it('reads the ids out of the server message', () => {
    expect(activeRunIds(BLOCKED)).toEqual(['e1', 'e2']);
    expect(activeRunIds('nothing to see')).toEqual([]);
  });
});

describe('ConfirmDeleteDialog', () => {
  it('states what goes and what stays, and needs the id typed before it deletes', async () => {
    const remove = vi.fn().mockResolvedValue(undefined);
    const { user, onDone } = setup(remove);

    expect(screen.getByText(/removes the session/)).toHaveTextContent('It stays: the run history');
    const button = screen.getByRole('button', { name: 'Delete' });
    expect(button).toBeDisabled();
    await user.type(screen.getByLabelText('Type s1 to confirm'), 's');
    expect(button).toBeDisabled();
    await user.type(screen.getByLabelText('Type s1 to confirm'), '1');
    await user.click(button);

    expect(remove).toHaveBeenCalledWith(false);
    expect(await screen.findByText('Deleted s1.')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Done' }));
    expect(onDone).toHaveBeenCalledTimes(1);
  });

  it('lists the active runs on a 409 and stops them only after a second confirmation', async () => {
    const remove = vi
      .fn()
      .mockRejectedValueOnce(new ApiError(409, BLOCKED))
      .mockResolvedValueOnce(undefined);
    const { user } = setup(remove);
    await user.type(screen.getByLabelText('Type s1 to confirm'), 's1');
    await user.click(screen.getByRole('button', { name: 'Delete' }));

    expect(await screen.findByText('Runs are still active')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'e1' })).toHaveAttribute('href', '/runs?text=e1');
    expect(screen.getByRole('link', { name: 'e2' })).toBeInTheDocument();
    expect(remove).toHaveBeenCalledTimes(1);

    await user.click(screen.getByRole('button', { name: 'Stop runs and delete' }));
    expect(remove).toHaveBeenCalledTimes(1);
    await user.click(screen.getByRole('button', { name: 'Yes, stop runs and delete' }));
    expect(remove).toHaveBeenLastCalledWith(true);
    expect(await screen.findByText('Deleted s1.')).toBeInTheDocument();
  });

  it('shows the whole message when a 409 names no runs, and keeps the stop button gated on the id', async () => {
    const remove = vi.fn().mockRejectedValue(new ApiError(409, 'Another conflict'));
    const { user } = setup(remove);
    await user.type(screen.getByLabelText('Type s1 to confirm'), 's1');
    await user.click(screen.getByRole('button', { name: 'Delete' }));

    expect(await screen.findByText(/Another conflict/)).toBeInTheDocument();
    await user.clear(screen.getByLabelText('Type s1 to confirm'));
    expect(screen.getByRole('button', { name: 'Stop runs and delete' })).toBeDisabled();
  });

  it('asks for an ADMIN key on a 403, shows other errors, and cancels', async () => {
    const remove = vi
      .fn()
      .mockRejectedValueOnce(new ApiError(403, 'x', undefined, 'forbidden'))
      .mockRejectedValueOnce(new Error('network down'));
    const { user, onClose } = setup(remove);
    await user.type(screen.getByLabelText('Type s1 to confirm'), 's1');
    await user.click(screen.getByRole('button', { name: 'Delete' }));
    expect(await screen.findByText('Deleting needs an ADMIN key.')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Delete' }));
    expect(await screen.findByText('network down')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(onClose).toHaveBeenCalledTimes(1);
  });
});
