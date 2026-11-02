// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect } from 'vitest';
import { scopeOptions, sessionOf } from '../scopes';
import { annotationLabel, annotationTarget } from '../annotations';
import type { ControlAnnotation } from '@/features/metrics/api';

describe('scopeOptions', () => {
  it('lists global, the sessions and each known workflow once, sorted', () => {
    const options = scopeOptions({
      sessions: [
        { sessionId: 's1', name: 'Alpha' },
        { sessionId: 's2', name: '' },
      ],
      workflowIds: ['wf-b', null, 'wf-a', undefined, 'wf-b'],
      current: 'global',
    });
    expect(options).toEqual([
      { value: 'global', label: 'Global' },
      { value: 'session:s1', label: 'Session Alpha', group: 'Sessions' },
      { value: 'session:s2', label: 'Session s2', group: 'Sessions' },
      { value: 'workflow:wf-a', label: 'Workflow wf-a', group: 'Workflows' },
      { value: 'workflow:wf-b', label: 'Workflow wf-b', group: 'Workflows' },
    ]);
  });

  it('keeps a scope from the URL that is not known yet', () => {
    const values = (current: string) =>
      scopeOptions({ sessions: [], workflowIds: [], current }).slice(1);
    expect(values('workflow:gone')).toEqual([
      { value: 'workflow:gone', label: 'Workflow gone', group: 'Workflows' },
    ]);
    expect(values('session:old')).toEqual([
      { value: 'session:old', label: 'Session old', group: 'Sessions' },
    ]);
    expect(values('odd')).toEqual([{ value: 'odd', label: 'odd' }]);
  });

  it('does not list the current scope twice', () => {
    const options = scopeOptions({
      sessions: [],
      workflowIds: ['wf'],
      current: 'workflow:wf',
    });
    expect(options.filter((o) => o.value === 'workflow:wf')).toHaveLength(1);
  });
});

describe('sessionOf', () => {
  it('reads the session of a session scope only', () => {
    expect(sessionOf('session:s1')).toBe('s1');
    expect(sessionOf('workflow:wf')).toBe('');
    expect(sessionOf('global')).toBe('');
  });
});

const mark = (over: Partial<ControlAnnotation>): ControlAnnotation => ({
  time: 0,
  executionId: 'run 1',
  sessionId: 's1',
  workflowId: 'wf',
  command: 'PauseWorkflowCommand',
  actor: 'guard',
  applied: true,
  reason: null,
  ...over,
});

describe('annotations', () => {
  it('labels a command with who sent it and why it was refused', () => {
    expect(annotationLabel(mark({}))).toBe('PauseWorkflowCommand by guard');
    expect(
      annotationLabel(mark({ applied: false, actor: null, reason: 'busy' }))
    ).toBe('PauseWorkflowCommand (refused: busy)');
    expect(annotationLabel(mark({ applied: false }))).toBe(
      'PauseWorkflowCommand by guard (refused)'
    );
  });

  it('leads to the run it steered, or to the audit without a run', () => {
    expect(annotationTarget(mark({}))).toBe(
      '/sessions/s1/workflow/wf?exec=run%201'
    );
    expect(annotationTarget(mark({ executionId: '' }))).toBe(
      '/sessions/s1/workflow/wf'
    );
    expect(annotationTarget(mark({ sessionId: null, workflowId: null }))).toBe(
      '/control?tab=audit'
    );
  });
});
