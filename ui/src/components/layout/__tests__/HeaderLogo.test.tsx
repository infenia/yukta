// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { BrowserRouter } from 'react-router';
import HeaderLogo from '../HeaderLogo';

describe('HeaderLogo', () => {
  it('is one link home named by the Yukta wordmark, with the mark beside it', () => {
    const { container } = render(
      <BrowserRouter>
        <HeaderLogo />
      </BrowserRouter>
    );
    const link = screen.getByRole('link', { name: 'Yukta' });
    expect(link).toHaveAttribute('href', '/');
    // a button inside a link is invalid HTML
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
    const mark = container.querySelector('img') as HTMLImageElement;
    expect(mark.src).toContain('favicon.svg');
    expect(mark).toHaveAttribute('alt', '');
    expect(link).toContainElement(mark);
  });
});
