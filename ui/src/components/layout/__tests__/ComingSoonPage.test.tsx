// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import ComingSoonPage from '../ComingSoonPage';

describe('ComingSoonPage', () => {
  describe('With Title Only', () => {
    it('should render title prop', () => {
      render(<ComingSoonPage title="Features" />);
      expect(screen.getByText('Features')).toBeInTheDocument();
    });

    it('should render default description when not provided', () => {
      render(<ComingSoonPage title="Features" />);
      expect(screen.getByText('This page is coming soon.')).toBeInTheDocument();
    });

    it('should render title as heading', () => {
      render(<ComingSoonPage title="My Feature" />);
      const heading = screen.getByRole('heading', { name: 'My Feature' });
      expect(heading).toBeInTheDocument();
    });
  });

  describe('With Custom Description', () => {
    it('should render custom description when provided', () => {
      render(
        <ComingSoonPage
          title="Analytics"
          description="Analytics dashboard will be available soon."
        />
      );
      expect(screen.getByText('Analytics dashboard will be available soon.')).toBeInTheDocument();
    });

    it('should not render default description when custom is provided', () => {
      render(
        <ComingSoonPage
          title="Analytics"
          description="Custom message"
        />
      );
      expect(screen.queryByText('This page is coming soon.')).not.toBeInTheDocument();
    });

    it('should render both title and description', () => {
      render(
        <ComingSoonPage
          title="Reports"
          description="Detailed reports coming Q4"
        />
      );
      expect(screen.getByText('Reports')).toBeInTheDocument();
      expect(screen.getByText('Detailed reports coming Q4')).toBeInTheDocument();
    });
  });

  describe('Layout & Structure', () => {
    it('should render main container with flex layout', () => {
      const { container } = render(<ComingSoonPage title="Test" />);
      const mainDiv = container.firstChild;
      expect(mainDiv).toHaveClass('flex', 'flex-1', 'items-center', 'justify-center');
    });

    it('should have correct outer container spacing', () => {
      const { container } = render(<ComingSoonPage title="Test" />);
      const mainDiv = container.firstChild;
      expect(mainDiv).toHaveClass('px-spacing-md', 'py-spacing-lg');
    });

    it('should render content wrapper with max width', () => {
      const { container } = render(<ComingSoonPage title="Test" />);
      const wrapper = container.querySelector('.max-w-md');
      expect(wrapper).toBeInTheDocument();
    });

    it('should center text content', () => {
      const { container } = render(<ComingSoonPage title="Test" />);
      const wrapper = container.querySelector('.text-center');
      expect(wrapper).toBeInTheDocument();
    });
  });

  describe('Icon Rendering', () => {
    it('should render construction icon', () => {
      render(<ComingSoonPage title="Test" />);
      const icon = screen.getByText('construction');
      expect(icon).toBeInTheDocument();
    });

    it('should have correct icon styling', () => {
      render(<ComingSoonPage title="Test" />);
      const icon = screen.getByText('construction');
      expect(icon).toHaveClass(
        'material-symbols-outlined',
        'text-6xl',
        'text-outline'
      );
    });

    it('should be marked as decorative', () => {
      render(<ComingSoonPage title="Test" />);
      const icon = screen.getByText('construction');
      expect(icon.closest('[aria-hidden="true"]')).toBeInTheDocument();
    });

    it('should have correct spacing after icon', () => {
      const { container } = render(<ComingSoonPage title="Test" />);
      const iconWrapper = container.querySelector('.mb-spacing-lg');
      expect(iconWrapper).toBeInTheDocument();
    });
  });

  describe('Title Styling', () => {
    it('should apply headline-lg styling to title', () => {
      render(<ComingSoonPage title="Features" />);
      const heading = screen.getByRole('heading');
      expect(heading).toHaveClass('text-headline-lg', 'mb-spacing-md');
    });

    it('should render h1 heading', () => {
      render(<ComingSoonPage title="Dashboard" />);
      const heading = screen.getByRole('heading', { level: 1 });
      expect(heading).toBeInTheDocument();
    });
  });

  describe('Description Styling', () => {
    it('should apply body-md styling to description', () => {
      render(<ComingSoonPage title="Test" description="Custom desc" />);
      const description = screen.getByText('Custom desc');
      expect(description).toHaveClass('text-body-md', 'text-on-surface-variant');
    });

    it('should be in a paragraph element', () => {
      render(<ComingSoonPage title="Test" description="Custom desc" />);
      const paragraph = screen.getByText('Custom desc').closest('p');
      expect(paragraph).toBeInTheDocument();
    });
  });

  describe('Default Description Behavior', () => {
    it('should show default text when description is empty string', () => {
      render(<ComingSoonPage title="Test" description="" />);
      expect(screen.getByText('This page is coming soon.')).toBeInTheDocument();
    });

    it('should show default text when description is undefined', () => {
      render(<ComingSoonPage title="Test" />);
      expect(screen.getByText('This page is coming soon.')).toBeInTheDocument();
    });

    it('should show custom text over default when provided', () => {
      render(
        <ComingSoonPage
          title="Test"
          description="Custom message"
        />
      );
      const custom = screen.getByText('Custom message');
      const defaultText = screen.queryByText('This page is coming soon.');
      expect(custom).toBeInTheDocument();
      expect(defaultText).not.toBeInTheDocument();
    });
  });

  describe('Complete Page Rendering', () => {
    it('should render complete page structure', () => {
      render(
        <ComingSoonPage
          title="Integrations"
          description="Coming in the next release"
        />
      );
      const icon = screen.getByText('construction');
      const heading = screen.getByText('Integrations');
      const description = screen.getByText('Coming in the next release');
      expect(icon).toBeInTheDocument();
      expect(heading).toBeInTheDocument();
      expect(description).toBeInTheDocument();
    });

    it('should render with vertical centering', () => {
      const { container } = render(<ComingSoonPage title="Test" />);
      const mainDiv = container.firstChild;
      expect(mainDiv).toHaveClass('flex', 'items-center', 'justify-center', 'flex-1');
    });

    it('should have responsive spacing', () => {
      const { container } = render(<ComingSoonPage title="Test" />);
      const mainDiv = container.firstChild;
      expect(mainDiv).toHaveClass('px-spacing-md', 'py-spacing-lg');
    });
  });

  describe('Props Types', () => {
    it('should accept title prop', () => {
      const { container } = render(<ComingSoonPage title="Test" />);
      expect(container).toBeInTheDocument();
    });

    it('should accept optional description prop', () => {
      const { container } = render(
        <ComingSoonPage title="Test" description="Optional" />
      );
      expect(container).toBeInTheDocument();
    });

    it('should render with only required title prop', () => {
      render(<ComingSoonPage title="Minimal" />);
      expect(screen.getByText('Minimal')).toBeInTheDocument();
    });
  });
});
