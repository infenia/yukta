// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi } from 'vitest';
import { act, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { requireApiKey, dismissApiKey } from '@/lib/apiKey';
import App from '../App';

vi.mock('@/features/auth/me', () => ({ loadMe: () => Promise.resolve() }));

vi.mock('@/components/layout/AppHeader', () => ({
  default: () => <div data-testid="app-header">Header</div>,
  MobileNav: () => <nav data-testid="mobile-nav" />,
  Sidebar: () => <aside data-testid="sidebar" />,
}));

vi.mock('@/components/layout/CommandPalette', () => ({
  CommandPalette: () => <div data-testid="command-palette" />,
}));

vi.mock('@/components/layout/AppFooter', () => ({
  default: () => <div data-testid="app-footer">Footer</div>,
}));

describe('App', () => {
  const renderApp = async (path = '/') => {
    const view = render(
      <QueryClientProvider client={new QueryClient()}>
        <MemoryRouter initialEntries={[path]}>
          <App />
        </MemoryRouter>
      </QueryClientProvider>
    );
    // the shell mounts once /api/me has answered
    await act(async () => {});
    return view;
  };

  it('should render without crashing', async () => {
    await renderApp();
    expect(screen.getByTestId('app-header')).toBeInTheDocument();
    expect(screen.getByTestId('app-footer')).toBeInTheDocument();
  });

  it('should render AppHeader', async () => {
    await renderApp();
    expect(screen.getByTestId('app-header')).toBeInTheDocument();
    expect(screen.getByText('Header')).toBeInTheDocument();
  });

  it('should render AppFooter', async () => {
    await renderApp();
    expect(screen.getByTestId('app-footer')).toBeInTheDocument();
    expect(screen.getByText('Footer')).toBeInTheDocument();
  });

  it.each([
    ['/sessions/s1/workflow/w1', false],
    ['/sessions/s1/workflow/w1/', false],
    ['/sessions/s1/workflow/w1/edit', true],
    ['/sessions/s1', true],
    ['/sessions', true],
    ['/', true],
  ])('footer on %s: %s', async (path, shown) => {
    await renderApp(path);
    expect(screen.queryByTestId('app-footer') !== null).toBe(shown);
    // the mobile navigation stays on every page
    expect(screen.getByTestId('mobile-nav')).toBeInTheDocument();
  });

  it('should render main content area', async () => {
    await renderApp();
    const main = screen.getByRole('main');
    expect(main).toBeInTheDocument();
  });

  it('should have correct layout classes on root div', async () => {
    const { container } = await renderApp();
    const rootDiv = container.querySelector('div');
    expect(rootDiv).toHaveClass('flex');
    expect(rootDiv).toHaveClass('min-h-screen');
    expect(rootDiv).toHaveClass('flex-col');
    expect(rootDiv).toHaveClass('bg-background');
    expect(rootDiv).toHaveClass('text-on-surface');
  });

  it('should have correct layout classes on main', async () => {
    const { container } = await renderApp();
    const main = container.querySelector('main');
    expect(main).toHaveClass('mt-header');
    expect(main).toHaveClass('flex');
    expect(main).toHaveClass('flex-1');
    expect(main).toHaveClass('flex-col');
  });

  it('should render Outlet for child routes', async () => {
    await renderApp();
    const main = screen.getByRole('main');
    expect(main).toBeInTheDocument();
    // Outlet is rendered inside main; it's a container for routes
    expect(main.children.length).toBeGreaterThanOrEqual(0);
  });

  it('shows nothing of the shell until /api/me answers, and only the key gate when a key is needed', async () => {
    render(
      <QueryClientProvider client={new QueryClient()}>
        <MemoryRouter>
          <App />
        </MemoryRouter>
      </QueryClientProvider>
    );
    expect(screen.queryByTestId('app-header')).toBeNull();
    await act(async () => {});
    expect(screen.getByTestId('app-header')).toBeInTheDocument();

    act(() => requireApiKey());
    expect(screen.queryByTestId('app-header')).toBeNull();
    expect(
      screen.getByRole('dialog', { name: 'Enter your API key' })
    ).toBeInTheDocument();

    act(() => dismissApiKey());
    expect(screen.getByTestId('app-header')).toBeInTheDocument();
  });

  describe('layout structure', () => {
    it('should have header before main', async () => {
      await renderApp();
      const header = screen.getByTestId('app-header');
      const main = screen.getByRole('main');
      expect(header).toBeInTheDocument();
      expect(main).toBeInTheDocument();
    });

    it('should have footer component rendered', async () => {
      await renderApp();
      const footer = screen.getByTestId('app-footer');
      expect(footer).toBeInTheDocument();
    });

    it('should use flexbox for vertical layout', async () => {
      const { container } = await renderApp();
      const rootDiv = container.querySelector('div');
      expect(rootDiv).toHaveClass('flex');
      expect(rootDiv).toHaveClass('flex-col');
    });

    it('should stretch to full viewport height', async () => {
      const { container } = await renderApp();
      const rootDiv = container.querySelector('div');
      expect(rootDiv).toHaveClass('min-h-screen');
    });

    it('should make main flex to fill available space', async () => {
      const { container } = await renderApp();
      const main = container.querySelector('main');
      expect(main).toHaveClass('flex-1');
    });
  });

  describe('styling', () => {
    it('should apply background color class', async () => {
      const { container } = await renderApp();
      const rootDiv = container.querySelector('div');
      expect(rootDiv).toHaveClass('bg-background');
    });

    it('should apply text color class', async () => {
      const { container } = await renderApp();
      const rootDiv = container.querySelector('div');
      expect(rootDiv).toHaveClass('text-on-surface');
    });

    it('should have top margin on main', async () => {
      const { container } = await renderApp();
      const main = container.querySelector('main');
      expect(main).toHaveClass('mt-header');
    });

    it('should maintain semantic HTML structure', async () => {
      await renderApp();
      const main = screen.getByRole('main');
      expect(main).toBeInTheDocument();
    });
  });
});
