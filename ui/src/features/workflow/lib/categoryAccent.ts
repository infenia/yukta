// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { PluginCategory } from '../types/workflow';

/** Accent colour (a CSS var reference) for a plugin category; neutral when unknown. */
export function categoryAccent(category: PluginCategory | null): string {
  switch (category) {
    case 'TRIGGER':
      return 'var(--color-cat-trigger)';
    case 'PROCESSOR':
      return 'var(--color-cat-processor)';
    case 'TERMINAL':
      return 'var(--color-cat-terminal)';
    default:
      return 'var(--color-outline)';
  }
}

/** Soft tint of the category accent, for icon tiles and chips. */
export function categoryTint(category: PluginCategory | null): string {
  switch (category) {
    case 'TRIGGER':
      return 'var(--color-cat-trigger-soft)';
    case 'PROCESSOR':
      return 'var(--color-cat-processor-soft)';
    case 'TERMINAL':
      return 'var(--color-cat-terminal-soft)';
    default:
      return 'var(--color-surface-container)';
  }
}
