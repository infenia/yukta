// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { fireEvent, screen } from '@testing-library/react';
import { renderWithProviders } from '@/test/utils/testUtils';
import { ApiError } from '@/lib/apiClient';
import { SupervisorCard } from '../SupervisorCard';

const state = vi.hoisted(() => ({
  attach: { mutate: vi.fn(), isPending: false, error: null as unknown },
  detach: { mutate: vi.fn(), isPending: false, error: null as unknown },
  replace: { mutate: vi.fn(), isPending: false, error: null as unknown },
}));

vi.mock('../useSupervisorData', () => ({
  useAttach: () => state.attach,
  useDetach: () => state.detach,
  useReplaceProfile: () => state.replace,
}));

const binding = {
  id: 'guard',
  profileId: 'p1',
  scope: 'session:s1',
  enabled: true,
};
const profile = { id: 'p1', type: 'rule-supervisor', dryRun: false, settings: {} };
const running = {
  id: 'guard',
  type: 'rule-supervisor',
  kind: 'SUPERVISOR',
  scope: 'SESSION',
  scopeSessionId: 's1',
  applied: 3,
  rejected: 1,
  recorded: 0,
  rateLimited: 0,
  dryRun: false,
};

const card = (props: Partial<Parameters<typeof SupervisorCard>[0]>) =>
  renderWithProviders(<SupervisorCard {...props} />);

describe('SupervisorCard', () => {
  beforeEach(() => {
    for (const s of [state.attach, state.detach, state.replace]) {
      s.mutate.mockReset();
      s.isPending = false;
      s.error = null;
    }
  });

  it('says a startup supervisor is configured at startup and not running without live numbers', () => {
    card({ running: { ...running, scope: 'GLOBAL', dryRun: true, kind: 'CONTROL_PLANE' } as never });
    expect(screen.getByText('Configured at startup.')).toBeInTheDocument();
    expect(screen.getByText(/control plane/)).toBeInTheDocument();
    expect(screen.getByText('Dry run')).toBeInTheDocument();
    expect(screen.getByText('Global')).toBeInTheDocument();
  });

  it('shows not running when there is no live supervisor', () => {
    card({ binding, profile });
    expect(screen.getByText('Not running.')).toBeInTheDocument();
  });

  it('labels a workflow scope with and without a session', () => {
    const { unmount } = card({
      running: { ...running, scope: 'WORKFLOW', scopeWorkflowId: 'w1' } as never,
    });
    expect(screen.getByText('Workflow s1/w1')).toBeInTheDocument();
    unmount();
    card({
      running: {
        ...running,
        scope: 'WORKFLOW',
        scopeSessionId: null,
        scopeWorkflowId: 'w1',
      } as never,
    });
    expect(screen.getByText('Workflow w1')).toBeInTheDocument();
  });

  it('marks a disabled binding and toggles enabled and dry run', () => {
    card({ binding: { ...binding, enabled: false }, profile, running });
    expect(screen.getByText('Disabled')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('switch', { name: 'Enabled' }));
    expect(state.attach.mutate).toHaveBeenCalledWith(
      expect.objectContaining({ id: 'guard', enabled: true })
    );
    fireEvent.click(screen.getByRole('switch', { name: 'Dry run' }));
    expect(state.replace.mutate).toHaveBeenCalledWith(
      expect.objectContaining({ id: 'p1', dryRun: true })
    );
  });

  it('asks before detaching', () => {
    card({ binding, profile, running });
    fireEvent.click(screen.getByRole('button', { name: 'Detach' }));
    fireEvent.click(screen.getByRole('button', { name: 'Keep' }));
    expect(state.detach.mutate).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: 'Detach' }));
    fireEvent.click(screen.getByRole('button', { name: 'Confirm detach' }));
    expect(state.detach.mutate).toHaveBeenCalledWith('guard');
  });

  it('explains a forbidden change and shows other errors', () => {
    state.attach.error = new ApiError(403);
    const { unmount } = card({ binding, profile, running });
    expect(screen.getByText('This needs an ADMIN key.')).toBeInTheDocument();
    unmount();

    state.attach.error = null;
    state.detach.error = new Error('busy');
    card({ binding, profile, running });
    expect(screen.getByText('busy')).toBeInTheDocument();
  });

  it('labels a session scope and names a card with nothing to show', () => {
    const { unmount } = card({ running: { ...running, scope: 'SESSION', scopeSessionId: 's1' } as never });
    expect(screen.getByText('Session s1')).toBeInTheDocument();
    unmount();
    card({});
    expect(screen.getByRole('article')).toBeInTheDocument();
  });
});
