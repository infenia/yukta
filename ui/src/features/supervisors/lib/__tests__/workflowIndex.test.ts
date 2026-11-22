// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, expect, it } from 'vitest';
import type { AttachmentView, Decision } from '../../types';
import { workflowIndex } from '../workflowIndex';

const att = (
  id: string,
  supervisorId: string,
  scope: string,
  extra: Partial<AttachmentView> = {}
): AttachmentView => ({
  id,
  supervisorId,
  scope,
  enabled: true,
  source: 'MANUAL',
  originSessionId: null,
  originWorkflowId: null,
  ...extra,
});
const decision = (workflowId: string, time: string): Decision => ({
  time,
  supervisorId: 'x',
  outcome: 'applied',
  command: 'PauseWorkflowCommand',
  executionId: 'e',
  reason: null,
  workflowId,
});
const none = { unowned: 0, unownedWorkflows: [] };

describe('workflowIndex', () => {
  it('groups the assignments of a workflow, with how each is attached', () => {
    const { rows } = workflowIndex(
      [
        att('1', 'lead', 'workflow:s/w'),
        att('2', 'guard', 'workflow:s/w//deploy.*', { enabled: false }),
        att('3', 'run-bot', 'run:s/w', { source: 'DEFINITION' }),
      ],
      none,
      []
    );
    expect(rows).toHaveLength(1);
    expect(rows[0].key).toBe('s/w');
    expect(rows[0].controllers).toEqual([
      {
        supervisorId: 'lead',
        level: 'workflow',
        enabled: true,
        source: 'MANUAL',
      },
      {
        supervisorId: 'guard',
        level: 'nodes',
        enabled: false,
        source: 'MANUAL',
      },
      {
        supervisorId: 'run-bot',
        level: 'run',
        enabled: true,
        source: 'DEFINITION',
      },
    ]);
    expect(rows[0].direct).toBe(false);
  });

  it('keeps a workflow with no session, and sets the other scopes apart', () => {
    const { rows, across } = workflowIndex(
      [
        att('1', 'lead', 'workflow:w'),
        att('2', 'lead', 'session:release'),
        att('3', 'audit', 'global'),
      ],
      none,
      []
    );
    expect(rows[0].sessionId).toBeNull();
    expect(rows[0].key).toBe('/w');
    expect(across).toEqual([
      { id: '2', supervisorId: 'lead', label: 'Session release' },
      { id: '3', supervisorId: 'audit', label: 'Everything (observes only)' },
    ]);
  });

  it('merges a workflow nobody owns with a switched-off assignment into one row', () => {
    const { rows } = workflowIndex(
      [att('1', 'lead', 'workflow:s/w', { enabled: false })],
      {
        unowned: 2,
        unownedWorkflows: [
          { sessionId: 's', workflowId: 'w' },
          { sessionId: 's', workflowId: 'free' },
        ],
      },
      []
    );
    expect(rows).toHaveLength(2);
    const w = rows.find((r) => r.key === 's/w');
    expect(w?.direct).toBe(true);
    expect(w?.controllers[0].enabled).toBe(false);
    expect(rows.find((r) => r.key === 's/free')?.controllers).toEqual([]);
  });

  it('counts the actions on a workflow and remembers the newest', () => {
    const { rows } = workflowIndex([att('1', 'lead', 'workflow:s/w')], none, [
      decision('w', '2026-11-22T10:00:00Z'),
      decision('w', '2026-11-22T12:00:00Z'),
      decision('w', '2026-11-22T11:00:00Z'),
      decision('other', '2026-11-22T13:00:00Z'),
    ]);
    expect(rows[0].actions).toEqual({
      count: 3,
      newest: '2026-11-22T12:00:00Z',
    });
  });

  it('orders acted-on workflows against idle ones and against each other, whichever comes first', () => {
    const { rows } = workflowIndex(
      [
        att('1', 'a', 'workflow:s/idle-b'),
        att('2', 'a', 'workflow:s/old'),
        att('3', 'a', 'workflow:s/new'),
        att('4', 'a', 'workflow:s/idle-a'),
      ],
      none,
      [
        decision('old', '2026-11-22T10:00:00Z'),
        decision('new', '2026-11-22T12:00:00Z'),
      ]
    );
    expect(rows.map((r) => r.workflowId)).toEqual([
      'new',
      'old',
      'idle-a',
      'idle-b',
    ]);
  });

  it('puts workflows with something switched off first, then the most recently acted on, then by name', () => {
    const { rows } = workflowIndex(
      [
        att('1', 'a', 'workflow:s/zeta'),
        att('2', 'a', 'workflow:s/alpha'),
        att('3', 'a', 'workflow:s/busy'),
        att('4', 'a', 'workflow:s/off', { enabled: false }),
        att('5', 'a', 'workflow:s/idle'),
      ],
      none,
      [decision('busy', '2026-11-22T12:00:00Z')]
    );
    expect(rows.map((r) => r.workflowId)).toEqual([
      'off',
      'busy',
      'alpha',
      'idle',
      'zeta',
    ]);
  });
});
