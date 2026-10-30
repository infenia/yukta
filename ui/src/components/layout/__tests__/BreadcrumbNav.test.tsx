// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import BreadcrumbNav from '../BreadcrumbNav';

const renderAt = (path: string) =>
  render(
    <MemoryRouter initialEntries={[path]}>
      <BreadcrumbNav className="mb-2" />
    </MemoryRouter>
  );

describe('BreadcrumbNav', () => {
  it('shows nothing on a top-level page', () => {
    const { container } = renderAt('/runs');
    expect(container).toBeEmptyDOMElement();
  });

  it('shows nothing outside a router', () => {
    const { container } = render(<BreadcrumbNav />);
    expect(container).toBeEmptyDOMElement();
  });

  it('links each level above the page, and marks the page itself', () => {
    renderAt('/sessions/dogfood/workflow/check/edit');
    const nav = screen.getByRole('navigation');
    expect(nav).toHaveClass('mb-2');
    expect(screen.getByRole('link', { name: 'Sessions' })).toHaveAttribute(
      'href',
      '/sessions'
    );
    expect(screen.getByRole('link', { name: 'dogfood' })).toHaveAttribute(
      'href',
      '/sessions/dogfood'
    );
    expect(screen.getByRole('link', { name: 'check' })).toHaveAttribute(
      'href',
      '/sessions/dogfood/workflow/check'
    );
    expect(screen.getByText('Edit')).toHaveAttribute('aria-current', 'page');
    expect(screen.getAllByText('chevron_right')).toHaveLength(3);
  });
});
