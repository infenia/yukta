// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useTheme, type Theme } from '@/lib/theme';

export default function ThemeToggle() {
  const { theme, setTheme } = useTheme();

  const toggleTheme = () => {
    const themes: Theme[] = ['light', 'dark', 'system'];
    const currentIndex = themes.indexOf(theme);
    const nextTheme = themes[(currentIndex + 1) % themes.length];
    setTheme(nextTheme);
  };

  const iconName =
    theme === 'dark'
      ? 'dark_mode'
      : theme === 'light'
        ? 'light_mode'
        : 'brightness_auto';

  return (
    <button
      type="button"
      onClick={toggleTheme}
      className="p-2 hover:bg-surface-container-low transition-colors"
      aria-label="Toggle theme"
      title={`Current: ${theme}`}
    >
      <span className="material-symbols-outlined">{iconName}</span>
    </button>
  );
}
