// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useSyncExternalStore } from 'react';

export type Theme = 'light' | 'dark' | 'system';

// Storage key for theme preference
const THEME_KEY = 'theme';

// Subscribers to theme changes
const subscribers: Set<() => void> = new Set();

// Kept for the page's lifetime so a choice still holds when storage is blocked.
let current: Theme | null = null;

function isTheme(value: unknown): value is Theme {
  return value === 'light' || value === 'dark' || value === 'system';
}

export function getTheme(): Theme {
  try {
    const stored = localStorage.getItem(THEME_KEY);
    return isTheme(stored) ? stored : 'system';
  } catch {
    // Storage is blocked (private mode, sandboxed frame): use the in-memory choice.
    return current ?? 'system';
  }
}

function applyTheme(theme: Theme): void {
  const isDark =
    theme === 'dark' ||
    (theme === 'system' &&
      window.matchMedia('(prefers-color-scheme: dark)').matches);

  if (isDark) {
    document.documentElement.classList.add('dark');
  } else {
    document.documentElement.classList.remove('dark');
  }
}

export function setTheme(theme: Theme): void {
  current = theme;
  try {
    localStorage.setItem(THEME_KEY, theme);
  } catch {
    // Not persisted; the choice still applies until the page is closed.
  }
  applyTheme(theme);

  // Dispatch custom event for cross-tab and local updates
  window.dispatchEvent(new CustomEvent('theme-change', { detail: { theme } }));
}

function subscribe(listener: () => void): () => void {
  subscribers.add(listener);

  // Listen for theme-change events (local and cross-tab via storage)
  const handleThemeChange = () => listener();
  const handleStorageChange = (e: StorageEvent) => {
    if (e.key === THEME_KEY) {
      applyTheme(getTheme());
      listener();
    }
  };

  // Listen for system preference changes (when theme is 'system')
  const mediaQuery = window.matchMedia('(prefers-color-scheme: dark)');
  const handleSystemPreferenceChange = () => {
    const theme = getTheme();
    if (theme === 'system') {
      applyTheme(theme);
      listener();
    }
  };

  window.addEventListener('theme-change', handleThemeChange);
  window.addEventListener('storage', handleStorageChange);
  mediaQuery.addEventListener('change', handleSystemPreferenceChange);

  return () => {
    subscribers.delete(listener);
    window.removeEventListener('theme-change', handleThemeChange);
    window.removeEventListener('storage', handleStorageChange);
    mediaQuery.removeEventListener('change', handleSystemPreferenceChange);
  };
}

export function useTheme() {
  const theme = useSyncExternalStore(subscribe, getTheme, getTheme);

  return { theme, setTheme };
}
