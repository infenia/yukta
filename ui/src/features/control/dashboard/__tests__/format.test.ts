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
  successPoints,
  failedPoints,
  opOf,
  OPERATIONS,
  agoText,
  lastRunAt,
  failedHours,
  failedRunIds,
  longestGcPause,
  healthConcerns,
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

describe('successPoints and failedPoints', () => {
  const MIN = 60_000;
  const cells = [
    { time: 0, runs: 4, failed: 1 },
    { time: 10 * MIN, runs: 6, failed: 1 },
    { time: 70 * MIN, runs: 10, failed: 0 },
    { time: 100 * MIN, runs: 0, failed: 0 },
  ];

  it('gives the success rate per half hour, oldest first, skipping a half hour with no runs', () => {
    expect(successPoints(cells)).toEqual([
      { t: 0, v: 80 },
      { t: 60 * MIN, v: 100 },
    ]);
    expect(successPoints([])).toEqual([]);
  });

  it('gives failed runs per five minutes for the last hour, quiet buckets as zero', () => {
    const points = failedPoints(
      [
        { time: 60 * MIN, runs: 3, failed: 2 },
        { time: 57 * MIN, runs: 1, failed: 1 },
      ],
      60 * MIN
    );
    expect(points).toHaveLength(12);
    expect(points[0]).toEqual({ t: 5 * MIN, v: 0 });
    expect(points.at(-1)).toEqual({ t: 60 * MIN, v: 2 });
    expect(points.at(-2)).toEqual({ t: 55 * MIN, v: 1 });
  });
});

describe('opOf', () => {
  it('names the kind of call a series key is for', () => {
    for (const op of OPERATIONS) {
      expect(opOf(`yukta.api.requests{op=${op}}|rate`)).toBe(op);
    }
    expect(opOf('yukta.executions.running')).toBeUndefined();
  });
});

describe('agoText', () => {
  it('reads as a person would say it', () => {
    expect(agoText(30_000)).toBe('just now');
    expect(agoText(12 * 60_000)).toBe('12 min ago');
    expect(agoText(3 * 3_600_000 + 5 * 60_000)).toBe('3 h ago');
    expect(agoText(50 * 3_600_000)).toBe('2 d ago');
  });
});

describe('lastRunAt', () => {
  it('is the minute of the newest cell that had runs', () => {
    expect(
      lastRunAt([
        { time: 5, runs: 1, failed: 0 },
        { time: 9, runs: 0, failed: 0 },
        { time: 7, runs: 2, failed: 0 },
      ])
    ).toBe(7);
    expect(lastRunAt([])).toBeUndefined();
  });
});

describe('failedHours', () => {
  const H = 3_600_000;
  it('adds up the failures of the last hour and of the hour before, ignoring the rest', () => {
    const now = 10 * H;
    expect(
      failedHours(
        [
          { time: now - 1, runs: 1, failed: 2 },
          { time: now - H, runs: 1, failed: 3 },
          { time: now - 2 * H, runs: 1, failed: 5 },
          { time: now + 1, runs: 1, failed: 7 },
        ],
        now
      )
    ).toEqual({ last: 2, previous: 3 });
  });
});

describe('failedRunIds', () => {
  it('lists the ids newest minute first, each with its minute', () => {
    expect(
      failedRunIds([
        { time: 1, runs: 1, failed: 1, failedRuns: ['a'] },
        { time: 3, runs: 2, failed: 2, failedRuns: ['b', 'c'] },
        { time: 2, runs: 0, failed: 0, failedRuns: [] },
      ])
    ).toEqual([
      { time: 3, id: 'b' },
      { time: 3, id: 'c' },
      { time: 1, id: 'a' },
    ]);
  });
});

describe('longestGcPause', () => {
  it('is the largest pause maximum, or zero', () => {
    expect(
      longestGcPause({
        'jvm.gc.pause{cause=a}|max': 40,
        'jvm.gc.pause{cause=b}|max': 90,
        'jvm.gc.pause{cause=b}|mean': 500,
      })
    ).toBe(90);
    expect(longestGcPause(undefined)).toBe(0);
  });
});

describe('healthConcerns', () => {
  const calm = {
    'jvm.memory.used{area=heap,id=a}': 50,
    'jvm.memory.max{area=heap,id=a}': 100,
    'yukta.system.memory.total': 100,
    'yukta.system.memory.free': 50,
    'jvm.gc.pause{cause=x}|max': 100,
  };

  it('has nothing to say while the numbers are fine, or missing', () => {
    expect(healthConcerns(calm)).toEqual([]);
    expect(healthConcerns(undefined)).toEqual([]);
  });

  it('names a full heap, full host memory, a long pause and dropped events', () => {
    expect(
      healthConcerns({
        'jvm.memory.used{area=heap,id=a}': 90,
        'jvm.memory.max{area=heap,id=a}': 100,
        'yukta.system.memory.total': 100,
        'yukta.system.memory.free': 5,
        'jvm.gc.pause{cause=x}|max': 2500,
        'yukta.tracker.dropped{type=log}|rate': 1,
      })
    ).toEqual([
      'Heap 90% full',
      'Host memory 95% used',
      'GC pause 2.5 s',
      'Events dropped',
    ]);
  });

  it('treats a host without a free-memory sample as using none of it', () => {
    expect(healthConcerns({ 'yukta.system.memory.total': 100 })).toEqual([
      'Host memory 100% used',
    ]);
  });
});
