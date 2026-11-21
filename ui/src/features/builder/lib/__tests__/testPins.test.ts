// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi, afterEach } from 'vitest';
import {
  PIN_EVENT,
  parseSample,
  payloadPaths,
  pinnedNodeIds,
  readPin,
  writePin,
} from '../testPins';

afterEach(() => {
  vi.restoreAllMocks();
  sessionStorage.clear();
});

describe('testPins', () => {
  it('round-trips a pin', () => {
    writePin('s', 'w', 'n', { sample: '{}', output: [1] });
    expect(readPin('s', 'w', 'n')).toEqual({ sample: '{}', output: [1] });
    expect(readPin('s', 'w', 'other')).toBeNull();
  });

  it('survives storage that throws', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    expect(readPin('s', 'w', 'n')).toBeNull();
    expect(() => writePin('s', 'w', 'n', { sample: '' })).not.toThrow();
  });

  it('parses only objects', () => {
    expect(parseSample(' ')).toEqual({});
    expect(parseSample('{"a":1}')).toEqual({ a: 1 });
    expect(parseSample('[1]')).toBeNull();
    expect(parseSample('null')).toBeNull();
    expect(parseSample('{')).toBeNull();
  });

  it('lists the leaf paths of an output', () => {
    expect(payloadPaths({ a: 1, b: { c: [1], d: null } })).toEqual([
      'a',
      'b.c',
      'b.d',
    ]);
    expect(payloadPaths('text')).toEqual([]);
    expect(payloadPaths({ a: { b: { c: { d: { e: 1 } } } } })).toEqual([
      'a.b.c.d',
    ]);
  });
});

describe('pinned nodes', () => {
  it('lists the nodes with an output and tells the canvas when a pin is written', () => {
    const seen = vi.fn();
    window.addEventListener(PIN_EVENT, seen);
    writePin('s', 'w', 'a', { sample: '{}', output: [1] });
    writePin('s', 'w', 'b', { sample: '{}' });
    window.removeEventListener(PIN_EVENT, seen);
    expect(seen).toHaveBeenCalledTimes(2);
    expect(pinnedNodeIds('s', 'w', ['a', 'b', 'c'])).toEqual(new Set(['a']));
  });
});
