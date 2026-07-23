// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { BrowserRouter } from 'react-router';
import BreadcrumbNav from '../BreadcrumbNav';
import * as breadcrumbUtils from '@/lib/breadcrumb-utils';

const renderWithRouter = (component: React.ReactElement) =>
  render(<BrowserRouter>{component}</BrowserRouter>);

describe('BreadcrumbNav', () => {
  describe('Root Path', () => {
    beforeEach(() => {
      vi.spyOn(breadcrumbUtils, 'parseBreadcrumbsFromPath').mockReturnValue([
        { label: 'Sessions', href: undefined, isCurrent: true },
      ]);
    });

    it('should render breadcrumb navigation', () => {
      renderWithRouter(<BreadcrumbNav />);
      expect(screen.getByText('Sessions')).toBeInTheDocument();
    });

    it('should render navigation div', () => {
      const { container } = renderWithRouter(<BreadcrumbNav />);
      const nav = container.querySelector('.flex.h-8');
      expect(nav).toBeInTheDocument();
    });

    it('should render current page as span, not link', () => {
      renderWithRouter(<BreadcrumbNav />);
      const span = screen.getByText('Sessions').closest('span');
      expect(span).toHaveClass('text-on-surface');
    });

    it('should not render separators for single item', () => {
      renderWithRouter(<BreadcrumbNav />);
      const chevrons = screen.queryAllByText('chevron_right');
      expect(chevrons).toHaveLength(0);
    });
  });

  describe('Nested Path', () => {
    beforeEach(() => {
      vi.spyOn(breadcrumbUtils, 'parseBreadcrumbsFromPath').mockReturnValue([
        { label: 'Sessions', href: '/sessions', isCurrent: false },
        { label: 'Workflows', href: undefined, isCurrent: true },
      ]);
    });

    it('should render multiple breadcrumb items', () => {
      renderWithRouter(<BreadcrumbNav />);
      expect(screen.getByText('Sessions')).toBeInTheDocument();
      expect(screen.getByText('Workflows')).toBeInTheDocument();
    });

    it('should render separator between items', () => {
      renderWithRouter(<BreadcrumbNav />);
      const chevrons = screen.getAllByText('chevron_right');
      expect(chevrons.length).toBeGreaterThan(0);
    });

    it('should render non-current items as links', () => {
      renderWithRouter(<BreadcrumbNav />);
      const sessionsLink = screen.getByRole('link', { name: 'Sessions' });
      expect(sessionsLink).toBeInTheDocument();
      expect(sessionsLink).toHaveAttribute('href', '/sessions');
    });

    it('should render current item as span', () => {
      renderWithRouter(<BreadcrumbNav />);
      const workflowsSpan = screen.getByText('Workflows').closest('span');
      expect(workflowsSpan).toHaveClass('text-on-surface');
    });

    it('should apply different styling to links vs current', () => {
      renderWithRouter(<BreadcrumbNav />);
      const link = screen.getByRole('link', { name: 'Sessions' });
      const current = screen.getByText('Workflows').closest('span');
      expect(link).toHaveClass('text-on-surface-variant', 'hover:text-on-surface');
      expect(current).toHaveClass('font-medium', 'text-on-surface');
    });
  });

  describe('Deep Nesting', () => {
    beforeEach(() => {
      vi.spyOn(breadcrumbUtils, 'parseBreadcrumbsFromPath').mockReturnValue([
        { label: 'Sessions', href: '/sessions', isCurrent: false },
        { label: 'Workflows', href: '/sessions/123/workflows', isCurrent: false },
        { label: 'Details', href: undefined, isCurrent: true },
      ]);
    });

    it('should render all breadcrumb levels', () => {
      renderWithRouter(<BreadcrumbNav />);
      expect(screen.getByText('Sessions')).toBeInTheDocument();
      expect(screen.getByText('Workflows')).toBeInTheDocument();
      expect(screen.getByText('Details')).toBeInTheDocument();
    });

    it('should render correct number of separators', () => {
      renderWithRouter(<BreadcrumbNav />);
      const chevrons = screen.getAllByText('chevron_right');
      expect(chevrons).toHaveLength(2); // Between 3 items
    });

    it('should navigate to intermediate breadcrumb', () => {
      renderWithRouter(<BreadcrumbNav />);
      const workflowsLink = screen.getByRole('link', { name: 'Workflows' });
      expect(workflowsLink).toHaveAttribute('href', '/sessions/123/workflows');
    });
  });

  describe('Styling & Layout', () => {
    beforeEach(() => {
      vi.spyOn(breadcrumbUtils, 'parseBreadcrumbsFromPath').mockReturnValue([
        { label: 'Sessions', href: undefined, isCurrent: true },
      ]);
    });

    it('should have correct container styling', () => {
      const { container } = renderWithRouter(<BreadcrumbNav />);
      const nav = container.querySelector('.flex.h-8');
      expect(nav).toHaveClass('px-spacing-md', 'w-full', 'items-center');
    });

    it('should have correct background styling', () => {
      const { container } = renderWithRouter(<BreadcrumbNav />);
      const nav = container.querySelector('.flex.h-8');
      expect(nav).toHaveClass('border-t', 'border-outline-variant', 'bg-surface-container-low');
    });

    it('should render breadcrumb list with correct gap', () => {
      const { container } = renderWithRouter(<BreadcrumbNav />);
      const list = container.querySelector('[class*="gap-spacing"]');
      expect(list).toHaveClass('gap-spacing-sm');
    });

    it('should render text with correct size', () => {
      renderWithRouter(<BreadcrumbNav />);
      const item = screen.getByText('Sessions').closest('span');
      expect(item).toHaveClass('text-xs');
    });
  });

  describe('Separator Styling', () => {
    beforeEach(() => {
      vi.spyOn(breadcrumbUtils, 'parseBreadcrumbsFromPath').mockReturnValue([
        { label: 'A', href: '/a', isCurrent: false },
        { label: 'B', href: '/b', isCurrent: false },
        { label: 'C', href: undefined, isCurrent: true },
      ]);
    });

    it('should style separator with correct color', () => {
      const { container } = renderWithRouter(<BreadcrumbNav />);
      const separators = container.querySelectorAll('[class*="text-outline-variant"]');
      expect(separators.length).toBeGreaterThan(0);
    });

    it('should use chevron_right icon for separators', () => {
      renderWithRouter(<BreadcrumbNav />);
      const chevrons = screen.getAllByText('chevron_right');
      chevrons.forEach((chevron) => {
        expect(chevron).toHaveClass('material-symbols-outlined', 'text-xs');
      });
    });
  });

  describe('Link Hover Effects', () => {
    beforeEach(() => {
      vi.spyOn(breadcrumbUtils, 'parseBreadcrumbsFromPath').mockReturnValue([
        { label: 'Sessions', href: '/sessions', isCurrent: false },
        { label: 'Current', href: undefined, isCurrent: true },
      ]);
    });

    it('should apply hover transition to links', () => {
      renderWithRouter(<BreadcrumbNav />);
      const link = screen.getByRole('link', { name: 'Sessions' });
      expect(link).toHaveClass('transition-colors', 'hover:text-on-surface');
    });

    it('should not apply hover effect to current item', () => {
      renderWithRouter(<BreadcrumbNav />);
      const current = screen.getByText('Current');
      expect(current).not.toHaveClass('hover:text-on-surface');
    });
  });

  describe('Accessibility', () => {
    beforeEach(() => {
      vi.spyOn(breadcrumbUtils, 'parseBreadcrumbsFromPath').mockReturnValue([
        { label: 'Sessions', href: '/sessions', isCurrent: false },
        { label: 'Current Page', href: undefined, isCurrent: true },
      ]);
    });

    it('should render navigation landmark', () => {
      const { container } = renderWithRouter(<BreadcrumbNav />);
      const nav = container.querySelector('nav');
      expect(nav).toBeInTheDocument();
    });

    it('should render breadcrumb list', () => {
      renderWithRouter(<BreadcrumbNav />);
      // BreadcrumbList and BreadcrumbItem are semantic elements
      expect(screen.getByText('Sessions')).toBeInTheDocument();
    });

    it('should have navigation link for non-current items', () => {
      renderWithRouter(<BreadcrumbNav />);
      const link = screen.getByRole('link', { name: 'Sessions' });
      expect(link).toHaveAttribute('href');
    });
  });

  describe('Edge Cases', () => {
    it('should render span for non-current item without href', () => {
      vi.spyOn(breadcrumbUtils, 'parseBreadcrumbsFromPath').mockReturnValue([
        { label: 'Parent', href: undefined, isCurrent: false },
        { label: 'Current', href: undefined, isCurrent: true },
      ]);

      renderWithRouter(<BreadcrumbNav />);
      const parentItem = screen.getByText('Parent');
      expect(parentItem).toBeInTheDocument();
      expect(parentItem).toHaveClass('text-on-surface-variant');
    });

    it('should render empty breadcrumbs list gracefully', () => {
      vi.spyOn(breadcrumbUtils, 'parseBreadcrumbsFromPath').mockReturnValue([]);

      const { container } = renderWithRouter(<BreadcrumbNav />);
      const nav = container.querySelector('nav');
      expect(nav).toBeInTheDocument();
    });
  });
});
