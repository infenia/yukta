// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { afterEach, describe, expect, it, vi } from 'vitest';
import { applyDensity, getDensity, setDensity } from '../density';

describe('density', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    localStorage.clear();
    setDensity('comfortable');
  });

  it('defaults to comfortable and stores the choice on the page', () => {
    localStorage.clear();
    expect(getDensity()).toBe('comfortable');
    setDensity('compact');
    expect(getDensity()).toBe('compact');
    expect(document.documentElement.dataset.density).toBe('compact');
    applyDensity('comfortable');
    expect(document.documentElement.dataset.density).toBe('comfortable');
  });

  it('keeps the choice when storage is blocked', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    setDensity('compact');
    expect(getDensity()).toBe('compact');
  });
});
