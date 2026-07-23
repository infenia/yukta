// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { BrowserRouter } from 'react-router';
import AppHeader from '../AppHeader';

const renderWithRouter = (component: React.ReactElement) =>
  render(<BrowserRouter>{component}</BrowserRouter>);

describe('AppHeader', () => {
  it('should render header element with fixed positioning', () => {
    const { container } = renderWithRouter(<AppHeader />);
    const header = container.querySelector('header');
    expect(header).toBeInTheDocument();
    expect(header).toHaveClass('fixed', 'inset-x-0', 'top-0', 'z-50');
  });

  it('should render with border and surface styling', () => {
    const { container } = renderWithRouter(<AppHeader />);
    const header = container.querySelector('header');
    expect(header).toHaveClass('border-b', 'border-outline-variant', 'bg-surface');
  });

  it('should render HeaderLogo component', () => {
    renderWithRouter(<AppHeader />);
    const logo = screen.getByAltText('Yukta');
    expect(logo).toBeInTheDocument();
  });

  it('should render ThemeToggle button', () => {
    renderWithRouter(<AppHeader />);
    const themeToggle = screen.getByLabelText('Toggle theme');
    expect(themeToggle).toBeInTheDocument();
  });

  it('should render BreadcrumbNav', () => {
    const { container } = renderWithRouter(<AppHeader />);
    const nav = container.querySelector('nav');
    expect(nav).toBeInTheDocument();
  });

  it('should have correct layout structure', () => {
    const { container } = renderWithRouter(<AppHeader />);
    const header = container.querySelector('header');
    const children = header?.children;
    expect(children?.length).toBeGreaterThanOrEqual(2);
  });

  it('should render top row with logo and theme toggle', () => {
    const { container } = renderWithRouter(<AppHeader />);
    const topRow = container.querySelector('.flex.h-12');
    expect(topRow).toBeInTheDocument();
    expect(topRow).toHaveClass('justify-between');
  });

  it('should apply correct spacing classes', () => {
    const { container } = renderWithRouter(<AppHeader />);
    const topRow = container.querySelector('.px-spacing-md');
    expect(topRow).toBeInTheDocument();
    expect(topRow).toHaveClass('pr-2');
  });

  it('should have breadcrumb navigation as second child', () => {
    const { container } = renderWithRouter(<AppHeader />);
    const header = container.querySelector('header');
    const secondChild = header?.children[1];
    expect(secondChild).toBeTruthy();
  });

  it('should render complete header hierarchy', () => {
    renderWithRouter(<AppHeader />);
    const yukta = screen.getByText('YUKTA');
    const toggleButton = screen.getByLabelText('Toggle theme');
    expect(yukta).toBeInTheDocument();
    expect(toggleButton).toBeInTheDocument();
  });
});
