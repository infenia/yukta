// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, afterEach, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { HowItWorks } from '../HowItWorks';

const KEY = 'yukta.supervisors.how-it-works.hidden';
const explanation = () =>
  screen.queryByRole('region', { name: 'How supervision works' });

afterEach(() => {
  localStorage.clear();
  vi.restoreAllMocks();
});

describe('HowItWorks', () => {
  it('explains supervision, hides on request and remembers the choice', async () => {
    const user = userEvent.setup();
    render(<HowItWorks />);
    expect(explanation()).toBeInTheDocument();

    await user.click(
      screen.getByRole('button', { name: 'Hide this explanation' })
    );
    expect(explanation()).not.toBeInTheDocument();
    expect(localStorage.getItem(KEY)).toBe('1');

    await user.click(
      screen.getByRole('button', { name: 'How supervision works' })
    );
    expect(explanation()).toBeInTheDocument();
    expect(localStorage.getItem(KEY)).toBe('0');
  });

  it('starts hidden when the choice was remembered', () => {
    localStorage.setItem(KEY, '1');
    render(<HowItWorks />);
    expect(explanation()).not.toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: 'How supervision works' })
    ).toBeInTheDocument();
  });

  it('still toggles when browser storage refuses access', async () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('storage is disabled');
    });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('storage is disabled');
    });
    const user = userEvent.setup();
    render(<HowItWorks />);
    expect(explanation()).toBeInTheDocument();

    await user.click(
      screen.getByRole('button', { name: 'Hide this explanation' })
    );
    await user.click(
      screen.getByRole('button', { name: 'How supervision works' })
    );
    expect(explanation()).toBeInTheDocument();
  });
});
