// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect } from 'vitest';
import { render } from '@testing-library/react';
import AppFooter from '../AppFooter';

describe('AppFooter', () => {
  it('should render footer element', () => {
    const { container } = render(<AppFooter />);
    const footer = container.querySelector('footer');
    expect(footer).toBeInTheDocument();
  });

  it('should have correct border and background styling', () => {
    const { container } = render(<AppFooter />);
    const footer = container.querySelector('footer');
    expect(footer).toHaveClass(
      'border-t',
      'border-outline',
      'bg-surface-container-low'
    );
  });

  it('should have correct text styling', () => {
    const { container } = render(<AppFooter />);
    const footer = container.querySelector('footer');
    expect(footer).toHaveClass('text-body-sm', 'text-on-surface-variant');
  });

  it('should render copyright text', () => {
    const { container } = render(<AppFooter />);
    const text = container.textContent;
    expect(text).toContain('© 2026 Infenia Private Limited');
    expect(text).toContain('All rights reserved');
  });

  it('should render paragraph element', () => {
    const { container } = render(<AppFooter />);
    const paragraph = container.querySelector('p');
    expect(paragraph).toBeInTheDocument();
  });

  it('should have correct container spacing', () => {
    const { container } = render(<AppFooter />);
    const innerDiv = container.querySelector('[class*="px-spacing"]');
    expect(innerDiv).toHaveClass('px-spacing-md', 'py-spacing-md');
  });

  it('should have text-center alignment', () => {
    const { container } = render(<AppFooter />);
    const innerDiv = container.querySelector('.text-center');
    expect(innerDiv).toBeInTheDocument();
  });

  it('should render complete footer structure', () => {
    const { container } = render(<AppFooter />);
    const footer = container.querySelector('footer');
    const contentDiv = footer?.querySelector('div');
    const paragraph = contentDiv?.querySelector('p');
    expect(footer).toBeInTheDocument();
    expect(contentDiv).toBeInTheDocument();
    expect(paragraph).toBeInTheDocument();
  });

  it('should contain copyright symbol', () => {
    const { container } = render(<AppFooter />);
    expect(container.textContent).toMatch(/©/);
  });

  it('should contain Infenia company name', () => {
    const { container } = render(<AppFooter />);
    expect(container.textContent).toContain('Infenia');
  });
});
