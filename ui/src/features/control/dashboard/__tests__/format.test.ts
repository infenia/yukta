// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect } from 'vitest';
import {
  formatMs,
  sumOf,
  tagOf,
  addUp,
  successRate,
  percentChange,
  formatChange,
  integrate,
  slowerIsWorse,
  usedOfBytes,
} from '../format';

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

describe('percentChange and formatChange', () => {
  it('is the change from the first to the last point', () => {
    const up = percentChange([
      { t: 1, v: 10 },
      { t: 2, v: 5 },
      { t: 3, v: 12 },
    ]);
    expect(up).toBe(20);
    expect(formatChange(up ?? 0)).toBe('+20%');
    expect(
      formatChange(
        percentChange([
          { t: 1, v: 4 },
          { t: 2, v: 3 },
        ]) ?? 0
      )
    ).toBe('-25%');
    expect(formatChange(0)).toBe('0%');
    expect(formatChange(undefined)).toBeUndefined();
  });

  it('colours a longer duration as a warning and a shorter one as a success', () => {
    expect(slowerIsWorse(12)).toBe('warning');
    expect(slowerIsWorse(-5)).toBe('success');
    expect(slowerIsWorse(0)).toBe('neutral');
    expect(slowerIsWorse(undefined)).toBe('neutral');
  });

  it('cannot be told from one point or from a start of zero', () => {
    expect(percentChange([{ t: 1, v: 3 }])).toBeUndefined();
    expect(
      percentChange([
        { t: 1, v: 0 },
        { t: 2, v: 3 },
      ])
    ).toBeUndefined();
  });
});

describe('integrate', () => {
  it('adds each rate times the seconds since the point before', () => {
    expect(
      integrate([
        { t: 0, v: 99 },
        { t: 5000, v: 2 },
        { t: 15_000, v: 1 },
      ])
    ).toBe(20);
  });

  it('is zero for fewer than two points', () => {
    expect(integrate([])).toBe(0);
    expect(integrate([{ t: 0, v: 5 }])).toBe(0);
  });
});

describe('usedOfBytes', () => {
  it('uses megabytes below a gigabyte and gigabytes above, in one unit', () => {
    expect(usedOfBytes(100e6, 400e6)).toBe('100 of 400 MB');
    expect(usedOfBytes(1.5e9, 4e9)).toBe('1.5 of 4.0 GB');
    expect(usedOfBytes(900e6, 2e9)).toBe('0.9 of 2.0 GB');
  });

  it('shows the used amount alone when the maximum is not known', () => {
    expect(usedOfBytes(100e6, 0)).toBe('100 MB');
    expect(usedOfBytes(100e6, -1)).toBe('100 MB');
  });
});
