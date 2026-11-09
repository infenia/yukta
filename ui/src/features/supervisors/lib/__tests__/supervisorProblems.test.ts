// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect } from 'vitest';
import { ApiError } from '@/lib/apiClient';
import { supervisorProblems } from '../supervisorProblems';

describe('supervisorProblems', () => {
  it('keeps only the errors about declared supervisors', () => {
    const error = new ApiError(400, 'bad', [
      { field: 'workflows.w.assignments[0].supervisor', message: 'missing' },
      { field: 'assignments[1].nodes', message: 'not allowed' },
      { field: 'name', message: 'other' },
    ]);
    expect(supervisorProblems(error)).toEqual([
      'workflows.w.assignments[0].supervisor: missing',
      'assignments[1].nodes: not allowed',
    ]);
  });

  it('has none for other errors', () => {
    expect(supervisorProblems(new ApiError(500))).toEqual([]);
    expect(supervisorProblems(new Error('network'))).toEqual([]);
    expect(supervisorProblems('x')).toEqual([]);
  });
});
