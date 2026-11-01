// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect } from 'vitest';
import { formatMs, sumOf, tagOf, addUp, successRate } from '../format';

describe('formatMs', () => {
  it('shows milliseconds, seconds and minutes', () => {
    expect(formatMs(850)).toBe('850 ms');
    expect(formatMs(1800)).toBe('1.8 s');
    expect(formatMs(125_000)).toBe('2 m 5 s');
  });

  it('shows a dash for a non-finite value', () => {
    expect(formatMs(Number.NaN)).toBe('–');
    expect(formatMs(Number.POSITIVE_INFINITY)).toBe('–');
  });
});

describe('sumOf', () => {
  it('adds the values whose key matches the prefix and suffix', () => {
    const values = { 'run{status=ok}': 2, 'run{status=failed}': 3, other: 10 };
    expect(sumOf(values, 'run')).toBe(5);
    expect(sumOf(values, 'run', '}')).toBe(5);
    expect(sumOf(values, 'run', 'x')).toBe(0);
  });

  it('is zero without values', () => {
    expect(sumOf(undefined, 'run')).toBe(0);
  });
});

describe('tagOf', () => {
  it('reads a tag from a series key', () => {
    expect(tagOf('rate{reason=limit,wf=a}', 'reason')).toBe('limit');
    expect(tagOf('rate{wf=a|reason=queue}', 'reason')).toBe('queue');
  });

  it('is undefined when the tag is absent', () => {
    expect(tagOf('rate{wf=a}', 'reason')).toBeUndefined();
  });
});

describe('addUp', () => {
  it('adds the series minute by minute, sorted by time', () => {
    const merged = addUp([
      [
        { t: 2, v: 1 },
        { t: 1, v: 1 },
      ],
      [{ t: 2, v: 4 }],
    ]);
    expect(merged).toEqual([
      { t: 1, v: 1 },
      { t: 2, v: 5 },
    ]);
  });
});

describe('successRate', () => {
  it('is a rounded percentage of the runs that did not fail', () => {
    expect(successRate(4, 1)).toBe(75);
  });

  it('is null without runs', () => {
    expect(successRate(0, 0)).toBeNull();
  });
});
