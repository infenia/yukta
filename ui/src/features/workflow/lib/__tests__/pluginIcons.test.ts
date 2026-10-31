// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, expect, it } from 'vitest';
import { pluginIcon } from '../pluginIcons';

describe('pluginIcon', () => {
  it('gives each decision plugin its own icon', () => {
    const types = ['CLASSIFY', 'GATE', 'SCORE', 'ASK', 'ANOMALY', 'DEDUPE'];
    const icons = types.map((type) => pluginIcon(type, 'PROCESSOR'));
    expect(new Set(icons).size).toBe(types.length);
    expect(icons).not.toContain(pluginIcon('UNKNOWN', 'PROCESSOR'));
  });

  it('falls back to the category icon for an unknown type', () => {
    expect(pluginIcon('UNKNOWN', 'TRIGGER')).toBe('input');
  });
});
