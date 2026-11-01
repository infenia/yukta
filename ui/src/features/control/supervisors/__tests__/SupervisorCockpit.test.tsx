// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { fireEvent, screen } from '@testing-library/react';
import { renderWithProviders } from '@/test/utils/testUtils';
import { SupervisorCockpit } from '../SupervisorCockpit';

type Q = { isPending: boolean; error: { message: string } | null; data?: unknown; refetch: () => void };
const q = (over: Partial<Q> = {}): Q => ({ isPending: false, error: null, data: [], refetch: vi.fn(), ...over });

const state = vi.hoisted(() => ({
  running: null as unknown,
  bindings: null as unknown,
  profiles: null as unknown,
  decisions: null as unknown,
  remove: { mutate: vi.fn(), error: null as { message: string } | null },
}));

vi.mock('../useSupervisorData', () => ({
  useRunningSupervisors: () => state.running,
  useBindings: () => state.bindings,
  useProfiles: () => state.profiles,
  useDecisions: () => state.decisions,
  useDeleteProfile: () => state.remove,
}));
vi.mock('../SupervisorCard', () => ({
  SupervisorCard: ({ running, binding }: { running?: { id: string }; binding?: { id: string } }) => (
    <div>card-{binding?.id ?? running?.id}</div>
  ),
}));
vi.mock('../ProfileDialog', () => ({
  ProfileDialog: ({ profile, onClose }: { profile?: { id: string }; onClose: () => void }) => (
    <button onClick={onClose}>profile-dialog-{profile?.id ?? 'new'}</button>
  ),
}));
vi.mock('../AttachDialog', () => ({
  AttachDialog: ({ onClose }: { onClose: () => void }) => (
    <button onClick={onClose}>attach-dialog</button>
  ),
}));

const profile = { id: 'p1', type: 'rule-supervisor', dryRun: false, settings: {} };

describe('SupervisorCockpit', () => {
  beforeEach(() => {
    state.running = q({ data: [] });
    state.bindings = q({ data: [] });
    state.profiles = q({ data: [profile] });
    state.decisions = q({ data: [] });
    state.remove = { mutate: vi.fn(), error: null };
  });

  it('shows loading while the supervisors load', () => {
    state.running = q({ isPending: true });
    renderWithProviders(<SupervisorCockpit />);
    expect(screen.getByText('Loading supervisors')).toBeInTheDocument();
  });

  it('shows the failure and retries both queries', () => {
    const refetch = vi.fn();
    state.running = q({ error: { message: 'down' }, refetch });
    state.bindings = q({ refetch: vi.fn() });
    renderWithProviders(<SupervisorCockpit />);
    expect(screen.getByText('Could not load supervisors')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /retry/i }));
    expect(refetch).toHaveBeenCalled();
  });

  it('says so when there are no supervisors', () => {
    renderWithProviders(<SupervisorCockpit />);
    expect(screen.getByText('No supervisors')).toBeInTheDocument();
  });

  it('shows a card for attached and for startup supervisors', () => {
    state.bindings = q({ data: [{ id: 'bound', profileId: 'p1', scope: 'global', enabled: true }] });
    state.running = q({ data: [{ id: 'bound' }, { id: 'boot' }] });
    renderWithProviders(<SupervisorCockpit />);
    expect(screen.getByText('card-bound')).toBeInTheDocument();
    expect(screen.getByText('card-boot')).toBeInTheDocument();
  });

  it('edits and deletes profiles', () => {
    renderWithProviders(<SupervisorCockpit />);
    fireEvent.click(screen.getByRole('button', { name: 'Edit profile p1' }));
    fireEvent.click(screen.getByRole('button', { name: 'profile-dialog-p1' }));
    expect(screen.queryByRole('button', { name: 'profile-dialog-p1' })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Delete profile p1' }));
    expect(state.remove.mutate).toHaveBeenCalledWith('p1');
  });

  it('creates a profile and attaches one from the header', () => {
    renderWithProviders(<SupervisorCockpit />);
    fireEvent.click(screen.getByRole('button', { name: 'New profile' }));
    expect(screen.getByRole('button', { name: 'profile-dialog-new' })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Attach supervisor' }));
    fireEvent.click(screen.getByRole('button', { name: 'attach-dialog' }));
    expect(screen.queryByRole('button', { name: 'attach-dialog' })).toBeNull();
  });

  it('shows the profile list states and a delete failure', () => {
    state.profiles = q({ isPending: true });
    const { unmount } = renderWithProviders(<SupervisorCockpit />);
    expect(screen.getAllByText('Loading').length).toBeGreaterThan(0);
    unmount();

    state.profiles = q({ error: { message: 'no profiles' } });
    const second = renderWithProviders(<SupervisorCockpit />);
    expect(screen.getByText('no profiles')).toBeInTheDocument();
    second.unmount();

    state.profiles = q({ data: [] });
    state.remove = { mutate: vi.fn(), error: { message: 'in use' } };
    renderWithProviders(<SupervisorCockpit />);
    expect(screen.getByText('No profile yet')).toBeInTheDocument();
    expect(screen.getByText('in use')).toBeInTheDocument();
  });

  it('lists decisions, with their reason, and the empty and failed states', () => {
    state.decisions = q({
      data: [
        { time: '2026-01-01T00:00:00Z', supervisorId: 'guard', executionId: 'e1', command: 'stop', outcome: 'rejected', reason: 'limit' },
        { time: '2026-01-01T00:00:01Z', supervisorId: 'guard', executionId: 'e2', command: 'pause', outcome: 'applied' },
      ],
    });
    const { unmount } = renderWithProviders(<SupervisorCockpit />);
    expect(screen.getByText('(limit)')).toBeInTheDocument();
    expect(screen.getByText('rejected')).toBeInTheDocument();
    unmount();

    state.decisions = q({ isPending: true });
    const loading = renderWithProviders(<SupervisorCockpit />);
    expect(screen.getAllByText('Loading').length).toBeGreaterThan(0);
    loading.unmount();

    state.decisions = q({ error: { message: 'feed down' } });
    const failed = renderWithProviders(<SupervisorCockpit />);
    expect(screen.getByText('feed down')).toBeInTheDocument();
    failed.unmount();

    state.decisions = q({ data: [] });
    renderWithProviders(<SupervisorCockpit />);
    expect(screen.getByText('No supervisor has decided anything yet')).toBeInTheDocument();
  });

  it('retries the decisions and the profiles when they fail', () => {
    const decisionsRefetch = vi.fn();
    const profilesRefetch = vi.fn();
    state.decisions = q({ error: { message: 'feed down' }, refetch: decisionsRefetch });
    state.profiles = q({ error: { message: 'no profiles' }, refetch: profilesRefetch });
    renderWithProviders(<SupervisorCockpit />);
    fireEvent.click(screen.getAllByRole('button', { name: /retry/i })[0]);
    fireEvent.click(screen.getAllByRole('button', { name: /retry/i })[1]);
    expect(decisionsRefetch).toHaveBeenCalled();
    expect(profilesRefetch).toHaveBeenCalled();
  });
});
