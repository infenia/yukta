// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { fireEvent, screen } from '@testing-library/react';
import { renderWithProviders } from '@/test/utils/testUtils';
import { ApiError } from '@/lib/apiClient';
import { AttachDialog } from '../AttachDialog';

const state = vi.hoisted(() => ({
  profiles: [] as { id: string; type: string }[],
  error: null as unknown,
  mutate: vi.fn(),
}));

vi.mock('../useSupervisorData', () => ({
  useProfiles: () => ({ data: state.profiles }),
  useAttach: () => ({
    mutate: state.mutate,
    error: state.error,
    isPending: false,
  }),
}));
vi.mock('@/features/sessions/hooks/useSessionSummaries', () => ({
  useSessionSummaries: () => ({
    data: [{ sessionId: 's1', name: '' }, { sessionId: 's2', name: 'Beta' }],
  }),
}));


const profile = { id: 'guard', type: 'rules' };

describe('AttachDialog', () => {
  beforeEach(() => {
    state.profiles = [profile];
    state.error = null;
    state.mutate.mockReset();
  });

  it('asks for a profile first when there are none', () => {
    state.profiles = [];
    renderWithProviders(<AttachDialog onClose={vi.fn()} />);
    expect(screen.getByText('Create a profile first.')).toBeInTheDocument();
  });

  it('asks for the missing session or workflow id', () => {
    renderWithProviders(
      <AttachDialog preset={{ kind: 'session' }} onClose={vi.fn()} />
    );
    expect(screen.getByText('Choose a session.')).toBeInTheDocument();
  });

  it('attaches the chosen profile to the chosen scope and closes', () => {
    const onClose = vi.fn();
    state.mutate.mockImplementation((_vars, opts: { onSuccess: () => void }) =>
      opts.onSuccess()
    );
    renderWithProviders(<AttachDialog onClose={onClose} />);
    fireEvent.change(screen.getByLabelText('Applies to'), {
      target: { value: 'session' },
    });
    fireEvent.change(screen.getByLabelText('Session'), {
      target: { value: 's2' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Attach' }));
    expect(state.mutate).toHaveBeenCalledWith(
      { profileId: 'guard', scope: 'session:s2', enabled: true },
      expect.any(Object)
    );
    expect(onClose).toHaveBeenCalled();
  });

  it('builds a workflow scope from the workflow id, with or without a session', () => {
    renderWithProviders(
      <AttachDialog
        preset={{ kind: 'workflow', workflowId: 'w1' }}
        onClose={vi.fn()}
      />
    );
    fireEvent.click(screen.getByRole('button', { name: 'Attach' }));
    expect(state.mutate).toHaveBeenCalledWith(
      expect.objectContaining({ scope: 'workflow:w1' }),
      expect.any(Object)
    );
  });

  it('explains a forbidden attach and shows other failures', () => {
    state.error = new ApiError(403);
    const { unmount } = renderWithProviders(
      <AttachDialog onClose={vi.fn()} />
    );
    expect(
      screen.getByText('Attaching supervisors needs an ADMIN key.')
    ).toBeInTheDocument();
    unmount();

    state.error = new Error('boom');
    renderWithProviders(<AttachDialog onClose={vi.fn()} />);
    expect(screen.getByText('boom')).toBeInTheDocument();
  });

  it('cancels without attaching', () => {
    const onClose = vi.fn();
    renderWithProviders(<AttachDialog onClose={onClose} />);
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(onClose).toHaveBeenCalled();
    expect(state.mutate).not.toHaveBeenCalled();
  });

  it('asks for a workflow id, types one and chooses a profile and the enabled switch', () => {
    state.profiles = [profile, { id: 'other', type: 'rules' }];
    renderWithProviders(<AttachDialog onClose={vi.fn()} />);
    fireEvent.change(screen.getByLabelText('Profile'), {
      target: { value: 'other' },
    });
    fireEvent.change(screen.getByLabelText('Applies to'), {
      target: { value: 'workflow' },
    });
    expect(screen.getByText('Give the workflow id.')).toBeInTheDocument();
    const input = screen.getByRole('textbox', { name: 'Workflow id' });
    fireEvent.change(input, { target: { value: 'w9' } });
    fireEvent.click(screen.getByRole('checkbox'));
    fireEvent.click(screen.getByRole('button', { name: 'Attach' }));
    expect(state.mutate).toHaveBeenCalledWith(
      { profileId: 'other', scope: 'workflow:w9', enabled: false },
      expect.any(Object)
    );
  });
});
