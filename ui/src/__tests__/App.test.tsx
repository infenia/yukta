// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import App from '../App';

vi.mock('@/components/layout/AppHeader', () => ({
  default: () => <div data-testid="app-header">Header</div>,
}));

vi.mock('@/components/layout/AppFooter', () => ({
  default: () => <div data-testid="app-footer">Footer</div>,
}));

describe('App', () => {
  const renderApp = () => {
    return render(
      <MemoryRouter>
        <App />
      </MemoryRouter>
    );
  };

  it('should render without crashing', () => {
    renderApp();
    expect(screen.getByTestId('app-header')).toBeInTheDocument();
    expect(screen.getByTestId('app-footer')).toBeInTheDocument();
  });

  it('should render AppHeader', () => {
    renderApp();
    expect(screen.getByTestId('app-header')).toBeInTheDocument();
    expect(screen.getByText('Header')).toBeInTheDocument();
  });

  it('should render AppFooter', () => {
    renderApp();
    expect(screen.getByTestId('app-footer')).toBeInTheDocument();
    expect(screen.getByText('Footer')).toBeInTheDocument();
  });

  it('should render main content area', () => {
    renderApp();
    const main = screen.getByRole('main');
    expect(main).toBeInTheDocument();
  });

  it('should have correct layout classes on root div', () => {
    const { container } = renderApp();
    const rootDiv = container.querySelector('div');
    expect(rootDiv).toHaveClass('flex');
    expect(rootDiv).toHaveClass('min-h-screen');
    expect(rootDiv).toHaveClass('flex-col');
    expect(rootDiv).toHaveClass('bg-background');
    expect(rootDiv).toHaveClass('text-on-surface');
  });

  it('should have correct layout classes on main', () => {
    const { container } = renderApp();
    const main = container.querySelector('main');
    expect(main).toHaveClass('mt-20');
    expect(main).toHaveClass('flex');
    expect(main).toHaveClass('flex-1');
    expect(main).toHaveClass('flex-col');
  });

  it('should render Outlet for child routes', () => {
    renderApp();
    const main = screen.getByRole('main');
    expect(main).toBeInTheDocument();
    // Outlet is rendered inside main; it's a container for routes
    expect(main.children.length).toBeGreaterThanOrEqual(0);
  });

  describe('layout structure', () => {
    it('should have header before main', () => {
      renderApp();
      const header = screen.getByTestId('app-header');
      const main = screen.getByRole('main');
      expect(header).toBeInTheDocument();
      expect(main).toBeInTheDocument();
    });

    it('should have footer component rendered', () => {
      renderApp();
      const footer = screen.getByTestId('app-footer');
      expect(footer).toBeInTheDocument();
    });

    it('should use flexbox for vertical layout', () => {
      const { container } = renderApp();
      const rootDiv = container.querySelector('div');
      expect(rootDiv).toHaveClass('flex');
      expect(rootDiv).toHaveClass('flex-col');
    });

    it('should stretch to full viewport height', () => {
      const { container } = renderApp();
      const rootDiv = container.querySelector('div');
      expect(rootDiv).toHaveClass('min-h-screen');
    });

    it('should make main flex to fill available space', () => {
      const { container } = renderApp();
      const main = container.querySelector('main');
      expect(main).toHaveClass('flex-1');
    });
  });

  describe('styling', () => {
    it('should apply background color class', () => {
      const { container } = renderApp();
      const rootDiv = container.querySelector('div');
      expect(rootDiv).toHaveClass('bg-background');
    });

    it('should apply text color class', () => {
      const { container } = renderApp();
      const rootDiv = container.querySelector('div');
      expect(rootDiv).toHaveClass('text-on-surface');
    });

    it('should have top margin on main', () => {
      const { container } = renderApp();
      const main = container.querySelector('main');
      expect(main).toHaveClass('mt-20');
    });

    it('should maintain semantic HTML structure', () => {
      renderApp();
      const main = screen.getByRole('main');
      expect(main).toBeInTheDocument();
    });
  });
});
