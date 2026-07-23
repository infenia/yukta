// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { SessionsFilterBar } from '../SessionsFilterBar';

describe('SessionsFilterBar', () => {
  it('should render search input', () => {
    render(
      <SessionsFilterBar
        data={[]}
        globalFilter=""
        onGlobalFilterChange={() => {}}
        onReset={() => {}}
      />
    );
    expect(screen.getByPlaceholderText(/search by name/i)).toBeInTheDocument();
  });

  it('should render reset button', () => {
    render(
      <SessionsFilterBar
        data={[]}
        globalFilter=""
        onGlobalFilterChange={() => {}}
        onReset={() => {}}
      />
    );
    expect(screen.getByLabelText('Reset filters')).toBeInTheDocument();
  });

  it('should call onGlobalFilterChange when typing in search', async () => {
    const user = userEvent.setup();
    const handleChange = vi.fn();

    render(
      <SessionsFilterBar
        data={[]}
        globalFilter=""
        onGlobalFilterChange={handleChange}
        onReset={() => {}}
      />
    );

    const input = screen.getByPlaceholderText(/search by name/i);
    await user.type(input, 'test');

    expect(handleChange).toHaveBeenCalled();
  });

  it('should call onReset when reset button clicked', async () => {
    const user = userEvent.setup();
    const handleReset = vi.fn();

    render(
      <SessionsFilterBar
        data={[]}
        globalFilter=""
        onGlobalFilterChange={() => {}}
        onReset={handleReset}
      />
    );

    const resetButton = screen.getByLabelText('Reset filters');
    await user.click(resetButton);

    expect(handleReset).toHaveBeenCalled();
  });

  it('should display current globalFilter value', () => {
    render(
      <SessionsFilterBar
        data={[]}
        globalFilter="test"
        onGlobalFilterChange={() => {}}
        onReset={() => {}}
      />
    );

    const input = screen.getByPlaceholderText(/search by name/i) as HTMLInputElement;
    expect(input.value).toBe('test');
  });
});
