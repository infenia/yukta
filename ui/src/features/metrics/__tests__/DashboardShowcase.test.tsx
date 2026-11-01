// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect } from 'vitest';
import { fireEvent, screen } from '@testing-library/react';
import { renderWithProviders } from '@/test/utils/testUtils';
import { DashboardShowcase } from '../DashboardShowcase';

describe('DashboardShowcase', () => {
  it('shows every dashboard primitive on sample data', () => {
    renderWithProviders(<DashboardShowcase />);
    expect(screen.getByTestId('dashboard-showcase')).toBeInTheDocument();
  });

  it('moves the concurrency knob with the keyboard', () => {
    renderWithProviders(<DashboardShowcase />);
    const knob = screen.getByRole('slider', { name: 'Max concurrent' });
    fireEvent.keyDown(knob, { key: 'ArrowUp' });
    expect(knob.getAttribute('aria-valuenow')).toBe('9');
  });
});
