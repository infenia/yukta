// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect } from 'vitest';
import { clampPercent, sparkPoints } from '../tones';

describe('clampPercent', () => {
  it('turns a value into a percentage of the maximum, clamped to 0..100', () => {
    expect(clampPercent(5, 10)).toBe(50);
    expect(clampPercent(20, 10)).toBe(100);
    expect(clampPercent(-1, 10)).toBe(0);
  });

  it('is zero without a positive maximum', () => {
    expect(clampPercent(5, 0)).toBe(0);
  });
});

describe('sparkPoints', () => {
  it('is empty for an empty series', () => {
    expect(sparkPoints([])).toBe('');
  });

  it('draws a level line for a flat series', () => {
    expect(sparkPoints([7, 7])).toBe('0.0,24.0 100.0,24.0');
  });

  it('spreads the points across the width, low values at the bottom', () => {
    expect(sparkPoints([0, 10], 100, 24)).toBe('0.0,24.0 100.0,0.0');
  });

  it('places a single point at the start', () => {
    expect(sparkPoints([3])).toBe('0.0,24.0');
  });
});
