// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { BrowserRouter, MemoryRouter } from 'react-router';
import userEvent from '@testing-library/user-event';
import AppHeader, { MobileNav } from '../AppHeader';

const renderWithRouter = (component: React.ReactElement) =>
  render(<BrowserRouter>{component}</BrowserRouter>);

describe('AppHeader', () => {
  it('should render header element with fixed positioning', () => {
    const { container } = renderWithRouter(<AppHeader />);
    const header = container.querySelector('header');
    expect(header).toBeInTheDocument();
    expect(header).toHaveClass('fixed', 'inset-x-0', 'top-0', 'z-50');
  });

  it('should render a translucent header with a hairline border', () => {
    const { container } = renderWithRouter(<AppHeader />);
    const header = container.querySelector('header');
    expect(header).toHaveClass(
      'border-b',
      'border-border',
      'bg-background/80',
      'backdrop-blur-md'
    );
  });

  it('should draw the decorative brand line at the top', () => {
    renderWithRouter(<AppHeader />);
    const line = screen.getByTestId('brand-line');
    expect(line).toHaveAttribute('aria-hidden', 'true');
    expect(line).toHaveClass('absolute', 'top-0', 'h-0.5');
  });

  it.each([
    ['/', 'Overview'],
    ['/sessions/s1', 'Sessions'],
    ['/sessions/s1/workflow/w1/edit', 'Sessions'],
    ['/plugins', 'Plugins'],
    ['/runs', 'Runs'],
    ['/control', 'Control'],
  ])('marks the section of %s as current', (path, label) => {
    render(
      <MemoryRouter initialEntries={[path]}>
        <AppHeader />
      </MemoryRouter>
    );
    const current = screen
      .getAllByRole('link')
      .filter((link) => link.getAttribute('aria-current') === 'page');
    expect(current).toHaveLength(1);
    expect(current[0]).toHaveTextContent(label);
  });

  it('does not treat a path that only shares a prefix as the section', () => {
    render(
      <MemoryRouter initialEntries={['/pluginsx']}>
        <AppHeader />
      </MemoryRouter>
    );
    expect(screen.getByRole('link', { name: 'Plugins' })).not.toHaveAttribute(
      'aria-current'
    );
  });

  it('should show the active route as an accent pill', () => {
    renderWithRouter(<AppHeader />);
    const active = screen.getByRole('link', { name: /Overview/ });
    expect(active).toHaveAttribute('aria-current', 'page');
    expect(active).toHaveClass('rounded-lg', 'bg-accent', 'font-semibold');
    const inactive = screen.getByRole('link', { name: /Plugins/ });
    expect(inactive).not.toHaveAttribute('aria-current');
    expect(inactive).toHaveClass('text-muted-foreground', 'hover:bg-muted');
  });

  it('should render HeaderLogo component', () => {
    renderWithRouter(<AppHeader />);
    const logo = screen.getByRole('link', { name: 'Yukta' });
    expect(logo).toBeInTheDocument();
  });

  it('should render ThemeToggle button', () => {
    renderWithRouter(<AppHeader />);
    const themeToggle = screen.getByLabelText('Toggle theme');
    expect(themeToggle).toBeInTheDocument();
  });

  it('should link the primary sections', () => {
    renderWithRouter(<AppHeader />);
    const nav = screen.getByRole('navigation', { name: 'Primary' });
    const links = Array.from(nav.querySelectorAll('a')).map((a) => [
      a.textContent?.replace(/^[a-z_]+/, ''),
      a.getAttribute('href'),
    ]);
    expect(links).toEqual([
      ['Overview', '/'],
      ['Sessions', '/sessions'],
      ['Runs', '/runs'],
      ['Plugins', '/plugins'],
      ['Control', '/control'],
    ]);
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

  it('puts the sections beside the logo, then search, then the theme toggle', () => {
    renderWithRouter(<AppHeader />);
    const order = [
      screen.getByRole('link', { name: /Yukta/ }),
      screen.getByRole('navigation', { name: 'Primary' }),
      screen.getByRole('button', { name: 'Search and jump to (⌘K)' }),
      screen.getByRole('button', { name: 'Toggle theme' }),
    ];
    for (let i = 1; i < order.length; i += 1) {
      expect(
        order[i - 1].compareDocumentPosition(order[i]) &
          Node.DOCUMENT_POSITION_FOLLOWING
      ).toBeTruthy();
    }
    expect(
      screen.getByText('Jump to a session, workflow or run…')
    ).toBeInTheDocument();
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
    const yukta = screen.getByText('Yukta');
    const toggleButton = screen.getByLabelText('Toggle theme');
    expect(yukta).toBeInTheDocument();
    expect(toggleButton).toBeInTheDocument();
  });

  it('opens the command palette from the search button', async () => {
    const opened = vi.fn();
    window.addEventListener('yukta:open-command-palette', opened);
    renderWithRouter(<AppHeader />);
    await userEvent.click(
      screen.getByRole('button', { name: 'Search and jump to (⌘K)' })
    );
    expect(opened).toHaveBeenCalledOnce();
    window.removeEventListener('yukta:open-command-palette', opened);
  });

  it('shows every nav label, not only icons', () => {
    renderWithRouter(<AppHeader />);
    for (const label of [
      'Overview',
      'Sessions',
      'Runs',
      'Plugins',
      'Control',
    ]) {
      expect(
        screen.getByRole('link', { name: new RegExp(label) })
      ).toHaveTextContent(label);
    }
  });
});

describe('MobileNav', () => {
  it.each([
    ['/', 'Overview'],
    ['/sessions', 'Sessions'],
    ['/runs', 'Runs'],
    ['/plugins/x', 'Plugins'],
  ])('marks the tab of %s as current', (path, label) => {
    render(
      <MemoryRouter initialEntries={[path]}>
        <MobileNav />
      </MemoryRouter>
    );
    const current = screen
      .getAllByRole('link')
      .filter((link) => link.getAttribute('aria-current') === 'page');
    expect(current).toHaveLength(1);
    expect(current[0]).toHaveTextContent(label);
    expect(current[0]).toHaveClass('text-primary');
  });

  it('is a labelled bottom bar hidden from wider screens', () => {
    render(
      <MemoryRouter>
        <MobileNav />
      </MemoryRouter>
    );
    const nav = screen.getByRole('navigation', { name: 'Primary' });
    expect(nav).toHaveClass('fixed', 'bottom-0', 'sm:hidden');
    expect(screen.getAllByRole('link')).toHaveLength(5);
  });
});
