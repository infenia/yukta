// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect } from 'vitest';
import {
  TIME_PLACEHOLDER,
  formatClockTime,
  formatDuration,
  formatLogTimestamp,
} from '../timeFormat';

describe('formatClockTime', () => {
  it('formats a local ISO timestamp as HH:MM:SS', () => {
    expect(formatClockTime('2026-07-26T14:22:01')).toBe('14:22:01');
  });

  it('pads single-digit components', () => {
    expect(formatClockTime('2026-07-26T04:02:09')).toBe('04:02:09');
  });

  it('returns the placeholder for null, undefined and empty values', () => {
    expect(formatClockTime(null)).toBe(TIME_PLACEHOLDER);
    expect(formatClockTime(undefined)).toBe(TIME_PLACEHOLDER);
    expect(formatClockTime('')).toBe(TIME_PLACEHOLDER);
  });

  it('returns the placeholder for unparsable values', () => {
    expect(formatClockTime('not-a-date')).toBe(TIME_PLACEHOLDER);
  });
});

describe('formatDuration', () => {
  it('formats seconds-only durations', () => {
    expect(formatDuration('2026-07-26T14:22:01', '2026-07-26T14:22:43')).toBe('42s');
  });

  it('formats minute durations like the mockup', () => {
    expect(formatDuration('2026-07-26T14:22:11', '2026-07-26T14:23:23')).toBe('1m 12s');
  });

  it('formats hour durations', () => {
    expect(formatDuration('2026-07-26T14:00:00', '2026-07-26T15:01:05')).toBe('1h 1m 5s');
  });

  it('returns -- when either endpoint is missing', () => {
    expect(formatDuration(null, '2026-07-26T14:22:01')).toBe('--');
    expect(formatDuration('2026-07-26T14:22:01', null)).toBe('--');
    expect(formatDuration(undefined, undefined)).toBe('--');
  });

  it('returns -- for unparsable or reversed ranges', () => {
    expect(formatDuration('nope', '2026-07-26T14:22:01')).toBe('--');
    expect(formatDuration('2026-07-26T14:22:01', 'nope')).toBe('--');
    expect(formatDuration('2026-07-26T15:00:00', '2026-07-26T14:00:00')).toBe('--');
  });
});

describe('formatLogTimestamp', () => {
  it('wraps the clock time in brackets', () => {
    expect(formatLogTimestamp('2026-07-26T14:22:15')).toBe('[14:22:15]');
  });

  it('wraps the placeholder when missing', () => {
    expect(formatLogTimestamp(null)).toBe(`[${TIME_PLACEHOLDER}]`);
  });
});
