// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi } from 'vitest';
import { fireEvent, screen } from '@testing-library/react';
import { renderWithProviders } from '@/test/utils/testUtils';
import { ScopedSupervisors } from '../ScopedSupervisors';

const bindings = vi.hoisted(() => ({ value: [] as unknown[], error: false }));

vi.mock('../useSupervisorData', () => ({
  useBindings: () =>
    bindings.error
      ? { isPending: false, error: new Error('down') }
      : { isPending: false, error: null, data: bindings.value },
}));
vi.mock('../AttachDialog', () => ({
  AttachDialog: ({ onClose }: { onClose: () => void }) => (
    <button onClick={onClose}>close-attach</button>
  ),
}));


describe('ScopedSupervisors', () => {
  it('lists the supervisors that apply, marking disabled ones', () => {
    bindings.error = false;
    bindings.value = [
      { id: 'b1', profileId: 'guard', scope: 'global', enabled: true },
      { id: 'b2', profileId: 'cap', scope: 'session:s1', enabled: false },
      { id: 'b3', profileId: 'other', scope: 'session:s9', enabled: true },
    ];
    renderWithProviders(
      <ScopedSupervisors sessionId="s1" className="mt-2" />
    );
    expect(screen.getByText(/guard · Global/)).toBeInTheDocument();
    expect(screen.getByText(/cap · Session s1/).textContent).toContain(
      '(disabled)'
    );
    expect(screen.queryByText(/other/)).toBeNull();
  });

  it('says none apply to a workflow when nothing matches', () => {
    bindings.error = false;
    bindings.value = [];
    renderWithProviders(<ScopedSupervisors sessionId="s1" workflowId="w1" />);
    expect(
      screen.getByText('None apply to this workflow.')
    ).toBeInTheDocument();
  });

  it('opens the attach dialog and closes it again', () => {
    bindings.error = false;
    bindings.value = [];
    renderWithProviders(<ScopedSupervisors sessionId="s1" />);
    fireEvent.click(screen.getByRole('button', { name: 'Attach supervisor' }));
    fireEvent.click(screen.getByRole('button', { name: 'close-attach' }));
    expect(
      screen.queryByRole('button', { name: 'close-attach' })
    ).toBeNull();
  });

  it('shows nothing when the bindings cannot be read', () => {
    bindings.error = true;
    renderWithProviders(<ScopedSupervisors sessionId="s1" />);
    expect(screen.queryByRole('region', { name: 'Supervisors' })).toBeNull();
  });
});
