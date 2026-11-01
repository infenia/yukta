// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { fireEvent, screen } from '@testing-library/react';
import { renderWithProviders } from '@/test/utils/testUtils';
import { ApiError } from '@/lib/apiClient';
import { ProfileDialog } from '../ProfileDialog';

const state = vi.hoisted(() => ({
  types: ['rule-supervisor', 'custom-x'] as string[],
  create: { mutate: vi.fn(), error: null as unknown },
  replace: { mutate: vi.fn(), error: null as unknown },
}));

vi.mock('../useSupervisorData', () => ({
  useSupervisorTypes: () => ({ data: state.types }),
  useCreateProfile: () => state.create,
  useReplaceProfile: () => state.replace,
}));

const existing = {
  id: 'guard',
  type: 'legacy-type',
  dryRun: true,
  settings: { rules: [], key: '***' },
};

describe('ProfileDialog', () => {
  beforeEach(() => {
    state.types = ['rule-supervisor', 'custom-x'];
    state.create = { mutate: vi.fn(), error: null };
    state.replace = { mutate: vi.fn(), error: null };
  });

  it('asks for an id before saving', () => {
    renderWithProviders(<ProfileDialog onClose={vi.fn()} />);
    fireEvent.click(screen.getByRole('button', { name: /save|create/i }));
    expect(state.create.mutate).not.toHaveBeenCalled();
  });

  it('creates a profile with the first type and empty settings', () => {
    renderWithProviders(<ProfileDialog onClose={vi.fn()} />);
    fireEvent.change(screen.getByRole('textbox', { name: 'Profile id' }), {
      target: { value: ' guard ' },
    });
    fireEvent.click(screen.getByRole('checkbox'));
    fireEvent.click(screen.getByRole('button', { name: /save|create/i }));
    expect(state.create.mutate).toHaveBeenCalledWith(
      { id: 'guard', type: 'rule-supervisor', settings: {}, dryRun: true },
      expect.any(Object)
    );
  });

  it('refuses settings that are not JSON', () => {
    renderWithProviders(<ProfileDialog onClose={vi.fn()} />);
    fireEvent.change(screen.getByRole('textbox', { name: 'Profile id' }), {
      target: { value: 'guard' },
    });
    fireEvent.change(screen.getByRole('textbox', { name: /Settings/ }), {
      target: { value: '{not json' },
    });
    expect(
      screen.getByRole('textbox', { name: /Settings/ }).getAttribute(
        'aria-invalid'
      )
    ).toBe('true');
    fireEvent.click(screen.getByRole('button', { name: /save|create/i }));
    expect(state.create.mutate).not.toHaveBeenCalled();
  });

  it('edits an existing profile, keeping its id and type', () => {
    const onClose = vi.fn();
    state.replace.mutate.mockImplementation(
      (_vars, opts: { onSuccess: () => void }) => opts.onSuccess()
    );
    renderWithProviders(
      <ProfileDialog profile={existing} onClose={onClose} />
    );
    expect(screen.getByRole('textbox', { name: 'Profile id' })).toBeDisabled();
    expect(screen.getByRole('option', { name: 'legacy-type' })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: /save|update/i }));
    expect(state.replace.mutate).toHaveBeenCalledWith(
      expect.objectContaining({ id: 'guard', type: 'legacy-type' }),
      expect.any(Object)
    );
    expect(onClose).toHaveBeenCalled();
  });

  it('explains a forbidden change and shows other failures', () => {
    state.create.error = new ApiError(403);
    const { unmount } = renderWithProviders(
      <ProfileDialog onClose={vi.fn()} />
    );
    expect(
      screen.getByText('Changing supervisors needs an ADMIN key.')
    ).toBeInTheDocument();
    unmount();

    state.create.error = new Error('exists');
    renderWithProviders(<ProfileDialog onClose={vi.fn()} />);
    expect(screen.getByText('exists')).toBeInTheDocument();
  });
});
