// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, expect, it } from 'vitest';
import type { ValidationResult } from '../../types';
import { issueCounts, problemsOf } from '../validationIssues';

const invalid = (rest: Partial<ValidationResult>): ValidationResult => ({
  valid: false,
  message: null,
  outputPorts: {},
  ...rest,
});
const detail = (
  nodeId: string | null,
  path: string | null,
  message: string
) => ({
  nodeId,
  path,
  code: 'C',
  message,
});

describe('problemsOf', () => {
  it('has none without a result, or for a valid one', () => {
    expect(problemsOf(null)).toEqual([]);
    expect(problemsOf({ valid: true, message: null, outputPorts: {} })).toEqual(
      []
    );
  });

  it('lists the details, sending engine settings to the Policy tab', () => {
    const result = invalid({
      details: [
        detail('a', null, 'one'),
        detail('b', 'engine.retry.maxBackoffMs', 'two'),
        detail(null, null, 'three'),
        detail(null, 'workflows.w.assignments[0]', 'assignments[0]: four'),
      ],
    });
    expect(problemsOf(result)).toEqual([
      { nodeId: 'a', message: 'one', tab: 'config' },
      { nodeId: 'b', message: 'two', tab: 'policy' },
      { nodeId: null, message: 'three', tab: 'config' },
    ]);
  });

  it('falls back to the message of a server that sends no details', () => {
    expect(problemsOf(invalid({ message: 'No trigger node' }))).toEqual([
      { nodeId: null, message: 'No trigger node', tab: 'config' },
    ]);
    expect(problemsOf(invalid({ message: 'assignments[0]: x' }))).toEqual([]);
    expect(problemsOf(invalid({ message: null }))).toEqual([]);
  });
});

describe('issueCounts', () => {
  it('counts the errors and warnings of each node, not those of the workflow', () => {
    const result = invalid({
      details: [
        detail('a', null, 'one'),
        detail('a', null, 'two'),
        detail(null, null, 'workflow'),
      ],
      warnings: [
        { nodeId: 'a', field: 'p', message: 'w' },
        { nodeId: 'b', field: 'p', message: 'w' },
      ],
    });
    expect(issueCounts(result)).toEqual({
      a: { errors: 2, warnings: 1 },
      b: { errors: 0, warnings: 1 },
    });
  });

  it('counts nothing without a result', () => {
    expect(issueCounts(null)).toEqual({});
  });
});
