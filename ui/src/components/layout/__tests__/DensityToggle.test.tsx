// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, expect, it } from 'vitest';
import { setDensity } from '@/lib/density';
import DensityToggle from '../DensityToggle';

afterEach(() => setDensity('comfortable'));

it('switches the page between comfortable and compact', async () => {
  render(<DensityToggle />);
  const button = screen.getByRole('button', { name: 'Compact layout' });
  expect(button).toHaveAttribute('aria-pressed', 'false');
  await userEvent.click(button);
  expect(button).toHaveAttribute('aria-pressed', 'true');
  expect(document.documentElement.dataset.density).toBe('compact');
  await userEvent.click(button);
  expect(document.documentElement.dataset.density).toBe('comfortable');
});
