// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '@/test/utils/testUtils';
import { FailedRunsDrawer } from '../FailedRunsDrawer';

describe('FailedRunsDrawer', () => {
  const runs = [
    { time: Date.UTC(2026, 0, 1, 13, 5), id: 'run-b' },
    { time: Date.UTC(2026, 0, 1, 12, 0), id: 'run/a' },
  ];

  it('lists each failed run as a link into the run search', () => {
    renderWithProviders(<FailedRunsDrawer runs={runs} onClose={vi.fn()} />);

    expect(
      screen.getByRole('dialog', { name: 'Failed runs' })
    ).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'run-b' })).toHaveAttribute(
      'href',
      '/runs?text=run-b'
    );
    expect(screen.getByRole('link', { name: 'run/a' })).toHaveAttribute(
      'href',
      '/runs?text=run%2Fa'
    );
  });

  it('closes from the Close button and from Escape', async () => {
    const onClose = vi.fn();
    renderWithProviders(<FailedRunsDrawer runs={runs} onClose={onClose} />);
    await userEvent.click(screen.getByRole('button', { name: 'Close' }));
    await userEvent.keyboard('{Escape}');
    expect(onClose).toHaveBeenCalledTimes(2);
  });
});
