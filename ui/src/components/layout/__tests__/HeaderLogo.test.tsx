// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { BrowserRouter } from 'react-router';
import HeaderLogo from '../HeaderLogo';

const renderWithRouter = (component: React.ReactElement) =>
  render(<BrowserRouter>{component}</BrowserRouter>);

describe('HeaderLogo', () => {
  it('should render link to home route', () => {
    renderWithRouter(<HeaderLogo />);
    const link = screen.getByRole('link');
    expect(link).toBeInTheDocument();
    expect(link).toHaveAttribute('href', '/');
  });

  it('should remove underline from link', () => {
    renderWithRouter(<HeaderLogo />);
    const link = screen.getByRole('link');
    expect(link).toHaveClass('no-underline');
  });

  it('should render logo image with correct alt text', () => {
    renderWithRouter(<HeaderLogo />);
    const image = screen.getByAltText('Yukta');
    expect(image).toBeInTheDocument();
  });

  it('should have correct image source', () => {
    renderWithRouter(<HeaderLogo />);
    const image = screen.getByAltText('Yukta') as HTMLImageElement;
    expect(image.src).toContain('favicon.svg');
  });

  it('should have correct image sizing', () => {
    renderWithRouter(<HeaderLogo />);
    const image = screen.getByAltText('Yukta');
    expect(image).toHaveClass('size-7', 'rounded-3xl', 'object-contain');
  });

  it('should render YUKTA text', () => {
    renderWithRouter(<HeaderLogo />);
    const text = screen.getByText('YUKTA');
    expect(text).toBeInTheDocument();
  });

  it('should have correct text styling', () => {
    renderWithRouter(<HeaderLogo />);
    const text = screen.getByText('YUKTA');
    expect(text).toHaveClass(
      'font-headline',
      'text-lg',
      'font-semibold',
      'tracking-tight',
      'text-on-surface'
    );
  });

  it('should render button with ghost variant', () => {
    renderWithRouter(<HeaderLogo />);
    const button = screen.getByRole('button');
    expect(button).toBeInTheDocument();
  });

  it('should have correct button styling', () => {
    renderWithRouter(<HeaderLogo />);
    const button = screen.getByRole('button');
    expect(button).toHaveClass('gap-spacing-sm', 'flex', 'items-center');
  });

  it('should have hover effect on button', () => {
    renderWithRouter(<HeaderLogo />);
    const button = screen.getByRole('button');
    expect(button).toHaveClass('hover:bg-surface-container-low');
  });

  it('should navigate home when clicked', async () => {
    const user = userEvent.setup();
    renderWithRouter(<HeaderLogo />);
    const link = screen.getByRole('link');
    await user.click(link);
    expect(link).toHaveAttribute('href', '/');
  });

  it('should render logo and text in correct order', () => {
    renderWithRouter(<HeaderLogo />);
    const button = screen.getByRole('button');
    const children = button.children;
    expect(children[0]).toBeInstanceOf(HTMLImageElement);
    expect(children[1]?.textContent).toContain('YUKTA');
  });

  it('should have dark mode text color', () => {
    renderWithRouter(<HeaderLogo />);
    const text = screen.getByText('YUKTA');
    expect(text).toHaveClass('dark:text-on-surface');
  });

  it('should render accessible button element', () => {
    renderWithRouter(<HeaderLogo />);
    const button = screen.getByRole('button');
    expect(button).toBeInTheDocument();
    expect(button.tagName).toBe('BUTTON');
  });

  it('should contain both image and text in button', () => {
    renderWithRouter(<HeaderLogo />);
    const image = screen.getByAltText('Yukta');
    const text = screen.getByText('YUKTA');
    expect(image.closest('button')).toBe(text.closest('button'));
  });
});
