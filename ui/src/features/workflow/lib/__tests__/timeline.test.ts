// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect } from 'vitest';
import { buildTimeline } from '../timeline';
import type { TaskProgress } from '../../types/workflow';

const task = (
  nodeId: string,
  status: string,
  startTime: string | null,
  endTime: string | null
): TaskProgress => ({ nodeId, module: 'M', status, startTime, endTime });

const T0 = Date.parse('2026-01-01T10:00:00Z');
const at = (s: number) => new Date(T0 + s * 1000).toISOString();

describe('buildTimeline', () => {
  it('lays rows out by start on the span from first start to last end', () => {
    const { rows, from, to } = buildTimeline(
      [task('b', 'SUCCESS', at(5), at(10)), task('a', 'SUCCESS', at(0), at(5))],
      T0
    );
    expect(rows.map((r) => r.nodeId)).toEqual(['a', 'b']);
    expect([from, to]).toEqual([T0, T0 + 10_000]);
    expect(rows[0]).toMatchObject({
      left: 0,
      width: 50,
      uiStatus: 'completed',
    });
    expect(rows[1]).toMatchObject({ left: 50, width: 50 });
  });

  it('runs an unfinished node up to now', () => {
    const { rows } = buildTimeline(
      [task('a', 'SUCCESS', at(0), at(5)), task('b', 'RUNNING', at(5), null)],
      T0 + 10_000
    );
    expect(rows[1]).toMatchObject({ endMs: T0 + 10_000, uiStatus: 'running' });
  });

  it('gives a node that took no time a visible bar', () => {
    const { rows } = buildTimeline(
      [task('a', 'SUCCESS', at(0), at(10)), task('b', 'SUCCESS', at(5), at(5))],
      T0
    );
    expect(rows[1].width).toBe(0.5);
  });

  it('leaves out nodes that have not started or have an unreadable start', () => {
    const { rows } = buildTimeline(
      [
        task('a', 'PENDING', null, null),
        task('b', 'SUCCESS', 'not a date', null),
      ],
      T0
    );
    expect(rows).toEqual([]);
  });

  it('keeps a bad end from drawing backwards', () => {
    const { rows } = buildTimeline([task('a', 'SUCCESS', at(5), at(0))], T0);
    expect(rows[0].endMs).toBe(rows[0].startMs);
  });
});
