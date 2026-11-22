// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect } from 'vitest';
import { startInputs, startPaths, startValue } from '../startValues';

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

describe('startInputs', () => {
  it('lists each path once with the type of its first use', () => {
    expect(
      startInputs([
        { type: 't', config: { a: '${start.label}', b: '${start.n:int}' } },
        {
          type: 't',
          config: { c: '${start.n:bool} ${start.on:boolean} ${start.x.y:str}' },
        },
        { type: 't' },
      ])
    ).toEqual([
      { path: 'label', type: 'text' },
      { path: 'n', type: 'number' },
      { path: 'on', type: 'toggle' },
      { path: 'x.y', type: 'text' },
    ]);
  });
});

describe('startInputs from a payload trigger', () => {
  const nodes = [
    { nodeId: 'in', type: 'API_TRIGGER', config: {} },
    {
      nodeId: 'dflt',
      type: 'MAPPER',
      config: {
        mapping: {
          text: "payload['text'] != null && payload['text'] != '' ? payload['text'] : 'Yukta'",
          n: 'payload.count + 1',
        },
      },
    },
    { nodeId: 'later', type: 'MAPPER', config: { x: 'payload.digit' } },
  ];
  const edges = [
    { source: 'in', target: 'dflt' },
    { source: 'dflt', target: 'later' },
  ];

  it('reads the keys of the nodes right after the trigger, with default and optional', () => {
    expect(startInputs(nodes, edges)).toEqual([
      { path: 'text', type: 'text', optional: true, fallback: 'Yukta' },
      { path: 'count', type: 'text', optional: false, fallback: undefined },
    ]);
  });

  it('finds none without a payload trigger', () => {
    expect(startInputs(nodes.slice(1), edges)).toEqual([]);
  });

  it('skips a node after the trigger that has no config', () => {
    expect(
      startInputs(
        [
          { nodeId: 'in', type: 'API_TRIGGER', config: {} },
          { nodeId: 'bare', type: 'MAPPER' },
        ],
        [{ source: 'in', target: 'bare' }]
      )
    ).toEqual([]);
  });

  it('keeps the start reference when a payload key of the same name is also read', () => {
    expect(
      startInputs(
        [
          { nodeId: 'in', type: 'API_TRIGGER', config: {} },
          {
            nodeId: 'a',
            type: 'MAPPER',
            config: { x: '${start.text:int}', y: 'payload.text' },
          },
        ],
        [{ source: 'in', target: 'a' }]
      )
    ).toEqual([{ path: 'text', type: 'number' }]);
  });
});
