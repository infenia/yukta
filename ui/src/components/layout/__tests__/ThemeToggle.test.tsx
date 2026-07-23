// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import ThemeToggle from '../ThemeToggle';
import * as themeModule from '@/lib/theme';

const mockSetTheme = vi.fn();

beforeEach(() => {
  vi.clearAllMocks();
});

describe('ThemeToggle', () => {
  describe('Light Theme', () => {
    beforeEach(() => {
      vi.spyOn(themeModule, 'useTheme').mockReturnValue({
        theme: 'light',
        setTheme: mockSetTheme,
      });
    });

    it('should render button with theme toggle label', () => {
      render(<ThemeToggle />);
      const button = screen.getByLabelText('Toggle theme');
      expect(button).toBeInTheDocument();
    });

    it('should display light_mode icon for light theme', () => {
      render(<ThemeToggle />);
      const icon = screen.getByText('light_mode');
      expect(icon).toBeInTheDocument();
    });

    it('should show light theme in title', () => {
      render(<ThemeToggle />);
      const button = screen.getByLabelText('Toggle theme');
      expect(button).toHaveAttribute('title', 'Current: light');
    });

    it('should cycle to dark theme on click', async () => {
      const user = userEvent.setup();
      render(<ThemeToggle />);
      const button = screen.getByLabelText('Toggle theme');
      await user.click(button);
      expect(mockSetTheme).toHaveBeenCalledWith('dark');
    });
  });

  describe('Dark Theme', () => {
    beforeEach(() => {
      vi.spyOn(themeModule, 'useTheme').mockReturnValue({
        theme: 'dark',
        setTheme: mockSetTheme,
      });
    });

    it('should display dark_mode icon for dark theme', () => {
      render(<ThemeToggle />);
      const icon = screen.getByText('dark_mode');
      expect(icon).toBeInTheDocument();
    });

    it('should show dark theme in title', () => {
      render(<ThemeToggle />);
      const button = screen.getByLabelText('Toggle theme');
      expect(button).toHaveAttribute('title', 'Current: dark');
    });

    it('should cycle to system theme on click', async () => {
      const user = userEvent.setup();
      render(<ThemeToggle />);
      const button = screen.getByLabelText('Toggle theme');
      await user.click(button);
      expect(mockSetTheme).toHaveBeenCalledWith('system');
    });
  });

  describe('System Theme', () => {
    beforeEach(() => {
      vi.spyOn(themeModule, 'useTheme').mockReturnValue({
        theme: 'system',
        setTheme: mockSetTheme,
      });
    });

    it('should display brightness_auto icon for system theme', () => {
      render(<ThemeToggle />);
      const icon = screen.getByText('brightness_auto');
      expect(icon).toBeInTheDocument();
    });

    it('should show system theme in title', () => {
      render(<ThemeToggle />);
      const button = screen.getByLabelText('Toggle theme');
      expect(button).toHaveAttribute('title', 'Current: system');
    });

    it('should cycle to light theme on click', async () => {
      const user = userEvent.setup();
      render(<ThemeToggle />);
      const button = screen.getByLabelText('Toggle theme');
      await user.click(button);
      expect(mockSetTheme).toHaveBeenCalledWith('light');
    });
  });

  describe('Theme Cycling', () => {
    it('should cycle through all themes in order: light -> dark', async () => {
      const user = userEvent.setup();
      vi.spyOn(themeModule, 'useTheme').mockReturnValue({
        theme: 'light',
        setTheme: mockSetTheme,
      });

      render(<ThemeToggle />);
      const button = screen.getByLabelText('Toggle theme');
      await user.click(button);
      expect(mockSetTheme).toHaveBeenCalledWith('dark');
    });

    it('should cycle through all themes in order: dark -> system', async () => {
      const user = userEvent.setup();
      vi.spyOn(themeModule, 'useTheme').mockReturnValue({
        theme: 'dark',
        setTheme: mockSetTheme,
      });

      render(<ThemeToggle />);
      const button = screen.getByLabelText('Toggle theme');
      await user.click(button);
      expect(mockSetTheme).toHaveBeenCalledWith('system');
    });

    it('should cycle through all themes in order: system -> light', async () => {
      const user = userEvent.setup();
      vi.spyOn(themeModule, 'useTheme').mockReturnValue({
        theme: 'system',
        setTheme: mockSetTheme,
      });

      render(<ThemeToggle />);
      const button = screen.getByLabelText('Toggle theme');
      await user.click(button);
      expect(mockSetTheme).toHaveBeenCalledWith('light');
    });
  });

  describe('Styling', () => {
    beforeEach(() => {
      vi.spyOn(themeModule, 'useTheme').mockReturnValue({
        theme: 'light',
        setTheme: mockSetTheme,
      });
    });

    it('should have correct button styling', () => {
      render(<ThemeToggle />);
      const button = screen.getByLabelText('Toggle theme');
      expect(button).toHaveClass('p-2', 'transition-colors', 'hover:bg-surface-container-low');
    });

    it('should have type button attribute', () => {
      render(<ThemeToggle />);
      const button = screen.getByLabelText('Toggle theme');
      expect(button).toHaveAttribute('type', 'button');
    });

    it('should have correct icon styling', () => {
      render(<ThemeToggle />);
      const icon = screen.getByText('light_mode');
      expect(icon).toHaveClass('material-symbols-outlined', 'scale-80');
    });
  });

  describe('Accessibility', () => {
    beforeEach(() => {
      vi.spyOn(themeModule, 'useTheme').mockReturnValue({
        theme: 'light',
        setTheme: mockSetTheme,
      });
    });

    it('should have aria-label for accessibility', () => {
      render(<ThemeToggle />);
      const button = screen.getByLabelText('Toggle theme');
      expect(button).toHaveAttribute('aria-label', 'Toggle theme');
    });

    it('should have title attribute with current theme', () => {
      render(<ThemeToggle />);
      const button = screen.getByLabelText('Toggle theme');
      expect(button).toHaveAttribute('title');
      expect(button.getAttribute('title')).toContain('Current:');
    });

    it('should be keyboard accessible', () => {
      vi.spyOn(themeModule, 'useTheme').mockReturnValue({
        theme: 'light',
        setTheme: mockSetTheme,
      });

      render(<ThemeToggle />);
      const button = screen.getByLabelText('Toggle theme');
      button.focus();
      expect(button).toHaveFocus();
    });
  });
});
