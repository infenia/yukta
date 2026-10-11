// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, expect, it } from 'vitest';
import { categoryAccent, categoryTint } from '../categoryAccent';

describe('categoryAccent', () => {
  it('maps each plugin category to its own accent and tint', () => {
    expect(categoryAccent('TRIGGER')).toBe('var(--color-cat-trigger)');
    expect(categoryAccent('PROCESSOR')).toBe('var(--color-cat-processor)');
    expect(categoryAccent('TERMINAL')).toBe('var(--color-cat-terminal)');
    expect(categoryTint('PROCESSOR')).toBe('var(--color-cat-processor-soft)');
  });

  it('falls back to neutral for an unknown category', () => {
    expect(categoryAccent(null)).toBe('var(--color-outline)');
    expect(categoryTint(null)).toBe('var(--color-surface-container)');
  });
});
