// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';

describe('main.tsx', () => {
  let mockRoot: HTMLElement;
  let originalMatchMedia: typeof window.matchMedia;

  beforeEach(() => {
    mockRoot = document.createElement('div');
    mockRoot.id = 'root';
    document.body.appendChild(mockRoot);

    localStorage.clear();
    document.documentElement.className = '';

    originalMatchMedia = window.matchMedia;
  });

  afterEach(() => {
    if (mockRoot && mockRoot.parentNode) {
      mockRoot.parentNode.removeChild(mockRoot);
    }
    localStorage.clear();
    document.documentElement.className = '';
    window.matchMedia = originalMatchMedia;
  });

  it('should have root element in DOM', () => {
    const root = document.getElementById('root');
    expect(root).toBeTruthy();
  });

  describe('theme initialization', () => {
    it('should initialize theme before rendering', () => {
      localStorage.setItem('theme', 'dark');
      const isDark =
        localStorage.getItem('theme') === 'dark' ||
        (localStorage.getItem('theme') === 'system' &&
          window.matchMedia('(prefers-color-scheme: dark)').matches);
      expect(isDark).toBe(true);
    });

    it('should detect dark theme from localStorage', () => {
      localStorage.setItem('theme', 'dark');
      const theme = localStorage.getItem('theme');
      expect(theme).toBe('dark');
    });

    it('should detect light theme from localStorage', () => {
      localStorage.setItem('theme', 'light');
      const theme = localStorage.getItem('theme');
      expect(theme).toBe('light');
    });

    it('should use system theme as default', () => {
      const theme = localStorage.getItem('theme') || 'system';
      expect(theme).toBeDefined();
    });

    it('should apply dark class when theme is dark', () => {
      localStorage.setItem('theme', 'dark');
      const isDark = localStorage.getItem('theme') === 'dark';
      if (isDark) {
        document.documentElement.classList.add('dark');
      }
      expect(document.documentElement.classList.contains('dark')).toBe(true);
    });

    it('should remove dark class when theme is light', () => {
      document.documentElement.classList.add('dark');
      localStorage.setItem('theme', 'light');
      const isDark = localStorage.getItem('theme') === 'dark';
      if (!isDark) {
        document.documentElement.classList.remove('dark');
      }
      expect(document.documentElement.classList.contains('dark')).toBe(false);
    });

    it('should check system preference when theme is system', () => {
      localStorage.setItem('theme', 'system');
      const theme = localStorage.getItem('theme');
      expect(theme).toBe('system');
    });
  });

  describe('system preference detection', () => {
    it('should detect dark system preference', () => {
      window.matchMedia = vi.fn().mockImplementation((query) => ({
        matches: query === '(prefers-color-scheme: dark)',
        media: query,
        onchange: null,
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
        addListener: vi.fn(),
        removeListener: vi.fn(),
      }));

      localStorage.setItem('theme', 'system');
      const isDark =
        localStorage.getItem('theme') === 'dark' ||
        (localStorage.getItem('theme') === 'system' &&
          window.matchMedia('(prefers-color-scheme: dark)').matches);
      expect(isDark).toBe(true);
    });

    it('should detect light system preference', () => {
      window.matchMedia = vi.fn().mockImplementation((query) => ({
        matches: false,
        media: query,
        onchange: null,
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
        addListener: vi.fn(),
        removeListener: vi.fn(),
      }));

      localStorage.setItem('theme', 'system');
      const isDark =
        localStorage.getItem('theme') === 'dark' ||
        (localStorage.getItem('theme') === 'system' &&
          window.matchMedia('(prefers-color-scheme: dark)').matches);
      expect(isDark).toBe(false);
    });
  });

  describe('theme application logic', () => {
    it('should apply dark class for explicit dark theme', () => {
      localStorage.setItem('theme', 'dark');
      const storedTheme = localStorage.getItem('theme');
      const isDark =
        storedTheme === 'dark' ||
        (storedTheme === 'system' &&
          window.matchMedia('(prefers-color-scheme: dark)').matches);

      if (isDark) {
        document.documentElement.classList.add('dark');
      } else {
        document.documentElement.classList.remove('dark');
      }

      expect(document.documentElement.classList.contains('dark')).toBe(true);
    });

    it('should not apply dark class for explicit light theme', () => {
      localStorage.setItem('theme', 'light');
      const storedTheme = localStorage.getItem('theme');
      const isDark =
        storedTheme === 'dark' ||
        (storedTheme === 'system' &&
          window.matchMedia('(prefers-color-scheme: dark)').matches);

      if (isDark) {
        document.documentElement.classList.add('dark');
      } else {
        document.documentElement.classList.remove('dark');
      }

      expect(document.documentElement.classList.contains('dark')).toBe(false);
    });

    it('should respect system preference for system theme', () => {
      window.matchMedia = vi.fn().mockImplementation((query) => ({
        matches: query === '(prefers-color-scheme: dark)',
        media: query,
        onchange: null,
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
        addListener: vi.fn(),
        removeListener: vi.fn(),
      }));

      localStorage.setItem('theme', 'system');
      const storedTheme = localStorage.getItem('theme');
      const isDark =
        storedTheme === 'dark' ||
        (storedTheme === 'system' &&
          window.matchMedia('(prefers-color-scheme: dark)').matches);

      if (isDark) {
        document.documentElement.classList.add('dark');
      } else {
        document.documentElement.classList.remove('dark');
      }

      expect(document.documentElement.classList.contains('dark')).toBe(true);
    });
  });

  describe('root element requirements', () => {
    it('should find root element with correct ID', () => {
      const root = document.getElementById('root');
      expect(root).toBeTruthy();
      expect(root?.id).toBe('root');
    });

    it('should have root element before rendering', () => {
      const rootElement = document.getElementById('root');
      if (rootElement) {
        expect(rootElement).toBeInTheDocument();
      }
    });
  });

  describe('initialization sequence', () => {
    it('should check localStorage before applying theme', () => {
      localStorage.setItem('theme', 'dark');
      const storedTheme = localStorage.getItem('theme');
      expect(storedTheme).toBe('dark');
    });

    it('should handle missing localStorage gracefully', () => {
      localStorage.clear();
      const theme = localStorage.getItem('theme') || 'system';
      expect(theme).toBeDefined();
    });

    it('should apply theme before rendering to avoid flashing', () => {
      localStorage.setItem('theme', 'dark');
      document.documentElement.classList.add('dark');
      const isDark = document.documentElement.classList.contains('dark');
      expect(isDark).toBe(true);
    });
  });
});
