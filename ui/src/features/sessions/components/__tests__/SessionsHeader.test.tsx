// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { SessionsHeader } from '../SessionsHeader';

describe('SessionsHeader', () => {
  it.each([
    { count: 0, expectedText: '0 sessions' },
    { count: 1, expectedText: '1 session' },
    { count: 5, expectedText: '5 sessions' },
  ])('should display "$expectedText" for $count sessions', ({ count, expectedText }) => {
    const { container } = render(<SessionsHeader sessionCount={count} />);
    const p = container.querySelector('p');
    expect(screen.getByRole('heading', { name: /Sessions/ })).toBeInTheDocument();
    expect(p?.textContent).toContain(expectedText);
    expect(p?.textContent).toContain('found.');
  });
});
