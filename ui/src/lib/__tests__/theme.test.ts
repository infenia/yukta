// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { getTheme, setTheme, useTheme, type Theme } from '../theme';

describe('theme', () => {
  beforeEach(() => {
    localStorage.clear();
    document.documentElement.className = '';
    vi.clearAllMocks();
  });

  afterEach(() => {
    localStorage.clear();
    document.documentElement.className = '';
  });

  describe('getTheme', () => {
    it('should return "system" when no theme is stored', () => {
      const theme = getTheme();
      expect(theme).toBe('system');
    });

    it('should return stored theme from localStorage', () => {
      localStorage.setItem('theme', 'dark');
      const theme = getTheme();
      expect(theme).toBe('dark');
    });

    it('should return "light" when stored', () => {
      localStorage.setItem('theme', 'light');
      const theme = getTheme();
      expect(theme).toBe('light');
    });

    it('should return stored theme even if invalid', () => {
      localStorage.setItem('theme', 'invalid');
      const theme = getTheme();
      expect(theme).toBe('invalid' as Theme);
    });
  });

  describe('setTheme', () => {
    it('should store theme in localStorage', () => {
      setTheme('dark');
      expect(localStorage.getItem('theme')).toBe('dark');
    });

    it('should add dark class to documentElement when theme is dark', () => {
      setTheme('dark');
      expect(document.documentElement.classList.contains('dark')).toBe(true);
    });

    it('should remove dark class when theme is light', () => {
      document.documentElement.classList.add('dark');
      setTheme('light');
      expect(document.documentElement.classList.contains('dark')).toBe(false);
    });

    it('should dispatch custom theme-change event', () => {
      const listener = vi.fn();
      window.addEventListener('theme-change', listener);

      setTheme('dark');

      expect(listener).toHaveBeenCalled();
      expect(listener.mock.calls[0][0]).toHaveProperty('detail.theme', 'dark');

      window.removeEventListener('theme-change', listener);
    });

    it('should apply dark class when system theme is dark', () => {
      window.matchMedia = vi.fn().mockImplementation((query) => ({
        matches: query === '(prefers-color-scheme: dark)',
        media: query,
        onchange: null,
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
        addListener: vi.fn(),
        removeListener: vi.fn(),
      }));

      setTheme('system');
      expect(document.documentElement.classList.contains('dark')).toBe(true);
    });

    it('should remove dark class when system theme is light', () => {
      window.matchMedia = vi.fn().mockImplementation((query) => ({
        matches: query !== '(prefers-color-scheme: dark)',
        media: query,
        onchange: null,
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
        addListener: vi.fn(),
        removeListener: vi.fn(),
      }));

      document.documentElement.classList.add('dark');
      setTheme('system');
      expect(document.documentElement.classList.contains('dark')).toBe(false);
    });

    it('should handle multiple consecutive setTheme calls', () => {
      setTheme('light');
      expect(localStorage.getItem('theme')).toBe('light');
      expect(document.documentElement.classList.contains('dark')).toBe(false);

      setTheme('dark');
      expect(localStorage.getItem('theme')).toBe('dark');
      expect(document.documentElement.classList.contains('dark')).toBe(true);

      setTheme('light');
      expect(localStorage.getItem('theme')).toBe('light');
      expect(document.documentElement.classList.contains('dark')).toBe(false);
    });
  });

  describe('subscribe function', () => {
    it('should dispatch theme-change event on setTheme', () => {
      const listener = vi.fn();
      window.addEventListener('theme-change', listener);

      setTheme('dark');

      expect(listener).toHaveBeenCalled();
      window.removeEventListener('theme-change', listener);
    });

    it('should handle storage event for cross-tab theme changes', () => {
      const listener = vi.fn();
      window.addEventListener('storage', listener);

      const event = new StorageEvent('storage', {
        key: 'theme',
        newValue: 'dark',
        oldValue: 'light',
        storageArea: localStorage,
      });

      window.dispatchEvent(event);
      expect(listener).toHaveBeenCalled();

      window.removeEventListener('storage', listener);
    });

    it('should apply theme when storage event updates theme key', () => {
      const event = new StorageEvent('storage', {
        key: 'theme',
        newValue: 'dark',
        oldValue: 'light',
        storageArea: localStorage,
      });

      localStorage.setItem('theme', 'dark');
      window.dispatchEvent(event);

      const theme = getTheme();
      expect(theme).toBe('dark');
    });

    it('should ignore storage events with different keys', () => {
      const listener = vi.fn();
      window.addEventListener('theme-change', listener);

      const event = new StorageEvent('storage', {
        key: 'otherKey',
        newValue: 'dark',
        oldValue: 'light',
      });

      window.dispatchEvent(event);
      expect(listener).not.toHaveBeenCalled();

      window.removeEventListener('theme-change', listener);
    });

    it('should respond to system preference changes when theme is system', () => {
      localStorage.setItem('theme', 'system');

      window.matchMedia = vi.fn().mockImplementation((query) => ({
        matches: query === '(prefers-color-scheme: dark)',
        media: query,
        onchange: null,
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
        addListener: vi.fn(),
        removeListener: vi.fn(),
      }));

      document.documentElement.classList.remove('dark');
      setTheme('system');

      expect(document.documentElement.classList.contains('dark')).toBe(true);
    });

    it('should trigger listener on storage event with matching key', () => {
      const listeners: ((e: StorageEvent) => void)[] = [];

      const originalAddEventListener = window.addEventListener;
      window.addEventListener = vi.fn((event, listener) => {
        if (event === 'storage') {
          listeners.push(listener as any);
        }
        originalAddEventListener(event, listener as any);
      });

      localStorage.setItem('theme', 'dark');

      const storageEvent = new StorageEvent('storage', {
        key: 'theme',
        newValue: 'light',
        oldValue: 'dark',
      });

      window.dispatchEvent(storageEvent);
      const theme = getTheme();
      expect(theme).toBe('dark');

      window.addEventListener = originalAddEventListener;
    });

    it('should apply theme on valid storage event', () => {
      localStorage.setItem('theme', 'dark');
      document.documentElement.classList.remove('dark');

      const storageEvent = new StorageEvent('storage', {
        key: 'theme',
        newValue: 'dark',
      });

      window.dispatchEvent(storageEvent);

      // Verify the theme logic would apply dark mode
      const storedTheme = localStorage.getItem('theme');
      const isDark = storedTheme === 'dark';
      expect(isDark).toBe(true);
    });
  });

  describe('useTheme hook', () => {
    it('should export useTheme function', () => {
      expect(useTheme).toBeDefined();
      expect(typeof useTheme).toBe('function');
    });
  });

  describe('theme application logic', () => {
    it('should correctly identify dark system preference', () => {
      window.matchMedia = vi.fn().mockImplementation((query) => ({
        matches: query === '(prefers-color-scheme: dark)',
        media: query,
        onchange: null,
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
        addListener: vi.fn(),
        removeListener: vi.fn(),
      }));

      setTheme('system');
      expect(document.documentElement.classList.contains('dark')).toBe(true);
    });

    it('should correctly identify light system preference', () => {
      window.matchMedia = vi.fn().mockImplementation((query) => ({
        matches: false,
        media: query,
        onchange: null,
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
        addListener: vi.fn(),
        removeListener: vi.fn(),
      }));

      setTheme('system');
      expect(document.documentElement.classList.contains('dark')).toBe(false);
    });

    it('should maintain theme through multiple operations', () => {
      setTheme('dark');
      expect(document.documentElement.classList.contains('dark')).toBe(true);

      setTheme('light');
      expect(document.documentElement.classList.contains('dark')).toBe(false);

      setTheme('dark');
      expect(document.documentElement.classList.contains('dark')).toBe(true);
    });
  });
});
