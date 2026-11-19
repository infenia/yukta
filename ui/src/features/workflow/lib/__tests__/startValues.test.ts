// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect } from 'vitest';
import { startPaths, startValue } from '../startValues';

describe('startPaths', () => {
  it('finds each start path once, without its type', () => {
    expect(
      startPaths({
        a: '${start.label}',
        b: ['${start.timeout:int} and ${start.label}'],
        c: { d: '${payload.x} ${env.Y} ${start.opts.n}' },
      })
    ).toEqual(['label', 'timeout', 'opts.n']);
  });

  it('finds none in a config without start references', () => {
    expect(startPaths({ a: 1 })).toEqual([]);
    expect(startPaths(undefined)).toEqual([]);
  });
});

describe('startValue', () => {
  const payload = { label: 'lint', opts: { n: 3, empty: null } };

  it('reads a dotted path', () => {
    expect(startValue(payload, 'label')).toBe('lint');
    expect(startValue(payload, 'opts.n')).toBe(3);
    expect(startValue(payload, 'opts.empty')).toBeNull();
  });

  it('is undefined for a path that is not there', () => {
    expect(startValue(payload, 'missing')).toBeUndefined();
    expect(startValue(payload, 'label.length')).toBeUndefined();
    expect(startValue(payload, 'opts.empty.x')).toBeUndefined();
  });
});
