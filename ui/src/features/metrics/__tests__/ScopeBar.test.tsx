// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect } from 'vitest';
import { screen, fireEvent, within } from '@testing-library/react';
import { renderWithProviders } from '@/test/utils/testUtils';
import { ScopeBar } from '../ScopeBar';

describe('ScopeBar', () => {
  it('lists the scopes and selects one', () => {
    renderWithProviders(
      <ScopeBar
        scopes={[
          { value: 'global', label: 'Global' },
          { value: 'alpha', label: 'Alpha' },
        ]}
      />
    );
    const scope = screen.getByLabelText('Scope') as HTMLSelectElement;
    expect(scope.value).toBe('global');
    fireEvent.change(scope, { target: { value: 'alpha' } });
    expect(scope.value).toBe('alpha');
  });

  it('marks the selected range and changes it', () => {
    renderWithProviders(<ScopeBar />);
    const group = screen.getByRole('group', { name: 'Range' });
    const first = within(group).getAllByRole('button')[0];
    fireEvent.click(first);
    expect(first.getAttribute('aria-pressed')).toBe('true');
  });

  it('toggles between live and paused', () => {
    renderWithProviders(<ScopeBar />);
    const live = screen.getByRole('button', { name: 'Live' });
    fireEvent.click(live);
    expect(screen.getByRole('button', { name: 'Paused' })).toBeTruthy();
  });
});
