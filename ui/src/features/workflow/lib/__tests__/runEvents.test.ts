// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect } from 'vitest';
import { runEvents } from '../runEvents';
import {
  createMockProgress,
  createMockTask,
} from '@/test/factories/workflowFactory';

describe('runEvents', () => {
  it('lists the run and node transitions oldest first, with status labels', () => {
    const progress = createMockProgress({
      status: 'FAILURE',
      startTime: '2026-10-30T10:00:00Z',
      endTime: '2026-10-30T10:00:05Z',
      tasks: [
        createMockTask({
          nodeId: 'b',
          status: 'FAILURE',
          startTime: '2026-10-30T10:00:02Z',
          endTime: '2026-10-30T10:00:05Z',
        }),
        createMockTask({
          nodeId: 'a',
          status: 'SUCCESS',
          startTime: '2026-10-30T10:00:00Z',
          endTime: '2026-10-30T10:00:01Z',
        }),
      ],
    });
    expect(
      runEvents(progress).map((e) => `${e.nodeId ?? 'run'} ${e.label}`)
    ).toEqual([
      'run Started',
      'a Started',
      'a Succeeded',
      'b Started',
      'b Failed',
      'run Failed',
    ]);
  });

  it('leaves out what has not happened yet', () => {
    const progress = createMockProgress({
      status: 'RUNNING',
      startTime: null,
      endTime: null,
      tasks: [
        createMockTask({
          nodeId: 'a',
          status: 'RUNNING',
          startTime: '2026-10-30T10:00:00Z',
          endTime: null,
        }),
        createMockTask({ nodeId: 'b', startTime: null, endTime: null }),
      ],
    });
    expect(runEvents(progress)).toEqual([
      { time: '2026-10-30T10:00:00Z', nodeId: 'a', label: 'Started' },
    ]);
  });
});
