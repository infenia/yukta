// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect } from 'vitest';
import { aggregateStatus, groupNodes, stageOf } from '../groups';
import {
  createMockGraphNode,
  createStagedGraph,
} from '@/test/factories/workflowFactory';

describe('stageOf', () => {
  it('takes the declared group, else the node-ID prefix before the first dot', () => {
    expect(
      stageOf(createMockGraphNode({ nodeId: 'unit', group: 'test' }))
    ).toBe('test');
    expect(stageOf(createMockGraphNode({ nodeId: 'lint.fail.x' }))).toBe(
      'lint'
    );
    expect(stageOf(createMockGraphNode({ nodeId: 'lint', group: '' }))).toBe(
      'lint'
    );
  });
});

describe('groupNodes', () => {
  it('keeps stages of two or more nodes, in first-appearance order', () => {
    const stages = groupNodes(createStagedGraph().nodes);
    expect([...stages.keys()]).toEqual(['lint', 'test']);
    expect(stages.get('lint')?.map((n) => n.nodeId)).toEqual([
      'lint',
      'lint.fail',
      'lint.fix',
    ]);
    expect(stages.get('test')?.map((n) => n.nodeId)).toEqual(['unit', 'e2e']);
  });
});

describe('aggregateStatus', () => {
  it.each([
    [['completed', 'failed', 'running'], 'failed'],
    [['completed', 'running', 'paused'], 'running'],
    [['completed', 'paused', 'pending'], 'paused'],
    [['skipped', 'skipped'], 'skipped'],
    [['completed', 'skipped'], 'completed'],
    [['completed', 'pending'], 'pending'],
  ] as const)('%j is %s', (statuses, expected) => {
    expect(aggregateStatus([...statuses])).toBe(expected);
  });
});
