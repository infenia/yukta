// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, expect, it } from 'vitest';
import { portColor } from '../portGlyph';

describe('portColor', () => {
  it('gives success-like ports the success token, regardless of case', () => {
    expect(portColor('success')).toBe('var(--color-success)');
    expect(portColor('OK')).toBe('var(--color-success)');
    expect(portColor('Passed')).toBe('var(--color-success)');
  });

  it('gives failure-like ports the error token', () => {
    expect(portColor('failure')).toBe('var(--color-error)');
    expect(portColor('Error')).toBe('var(--color-error)');
    expect(portColor('no')).toBe('var(--color-error)');
  });

  it('gives warning-like ports the warning token', () => {
    expect(portColor('warning')).toBe('var(--color-warning)');
    expect(portColor('RETRY')).toBe('var(--color-warning)');
    expect(portColor('timeout')).toBe('var(--color-warning)');
    expect(portColor('skipped')).toBe('var(--color-warning)');
  });

  it('gives the default port the node accent', () => {
    expect(portColor('default')).toBe('var(--node-accent)');
    expect(portColor(null)).toBe('var(--node-accent)');
  });

  it('picks the same spread color for the same custom port name', () => {
    const first = portColor('invalid');
    expect(first).toMatch(/^var\(--/);
    expect(portColor('Invalid')).toBe(first);
    expect(portColor('invalid')).toBe(first);
  });
});
