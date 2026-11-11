// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect } from 'vitest';
import { ownerLabel, parseScope, targetLabel } from '../ownership';
import { hierarchyRows } from '../hierarchy';
import { operationRows } from '../operations';
import { categoryColor, decisionMaker } from '../decisionMakers';

describe('parseScope and targetLabel', () => {
  it('reads every scope form', () => {
    expect(parseScope('global').kind).toBe('global');
    expect(targetLabel(parseScope('global'))).toBe(
      'Everything (observes only)'
    );
    expect(targetLabel(parseScope('server'))).toBe('The server');
    expect(parseScope('session:s1')).toEqual({
      kind: 'session',
      sessionId: 's1',
      workflowId: null,
      selectors: [],
    });
    expect(targetLabel(parseScope('session:s1'))).toBe('Session s1');
    expect(parseScope('run:s1/w1')).toMatchObject({
      kind: 'run',
      sessionId: 's1',
      workflowId: 'w1',
    });
    expect(targetLabel(parseScope('run:s1/w1'))).toBe('s1 / w1 · run level');
    expect(targetLabel(parseScope('workflow:w1'))).toBe('w1 · whole workflow');
    expect(parseScope('workflow:s1/w1//build.*,deploy.test')).toMatchObject({
      kind: 'workflow',
      sessionId: 's1',
      workflowId: 'w1',
      selectors: ['build.*', 'deploy.test'],
    });
    expect(targetLabel(parseScope('workflow:s1/w1//a,b'))).toBe(
      's1 / w1 · a, b'
    );
  });

  it('names the default owner', () => {
    expect(ownerLabel('default')).toBe('Direct control');
    expect(ownerLabel(null)).toBe('Direct control');
    expect(ownerLabel(undefined)).toBe('Direct control');
    expect(ownerLabel('guard')).toBe('guard');
  });
});

describe('hierarchyRows', () => {
  it('puts a child under each parent, says where else it is, and keeps cycles finite', () => {
    const rows = hierarchyRows([
      { id: 'lead' },
      { id: 'ops', parents: ['gone'] },
      { id: 'agent', parents: ['lead', 'ops'] },
      { id: 'a', parents: ['b'] },
      { id: 'b', parents: ['a'] },
    ]);
    expect(rows).toEqual([
      { id: 'lead', depth: 0, alsoUnder: [] },
      { id: 'agent', depth: 1, alsoUnder: ['ops'] },
      { id: 'ops', depth: 0, alsoUnder: [] },
      { id: 'agent', depth: 1, alsoUnder: ['lead'] },
      { id: 'a', depth: 0, alsoUnder: [] },
      { id: 'b', depth: 1, alsoUnder: [] },
    ]);
  });
});

describe('operationRows', () => {
  it('reads allowedCommands, initiative and operatorAccess by group', () => {
    const rows = operationRows(
      {
        allowedCommands: [
          'PauseWorkflowCommand',
          'StopWorkflowCommand',
          'StopNodeCommand',
        ],
        initiative: ['PauseWorkflowCommand'],
        operatorAccess: ['StopWorkflowCommand', 'StopNodeCommand'],
      },
      []
    );
    expect(rows[0]).toEqual({
      label: 'Pause and resume',
      send: 'some',
      onItsOwn: 'some',
      people: 'none',
    });
    expect(rows[1]).toEqual({
      label: 'Stop',
      send: 'all',
      onItsOwn: 'none',
      people: 'all',
    });
  });

  it('falls back to the engine default, where null is every command', () => {
    expect(operationRows({}, null).every((r) => r.send === 'all')).toBe(true);
    expect(operationRows({}, []).every((r) => r.send === 'none')).toBe(true);
    expect(
      operationRows({ allowedCommands: 'bad' }, []).every(
        (r) => r.send === 'all'
      )
    ).toBe(true);
  });
});

describe('decisionMaker', () => {
  it('names and colours a type from the catalog, or falls back', () => {
    const catalog = [
      {
        type: 'http',
        displayName: 'External system',
        category: 'EXTERNAL_SYSTEM' as const,
        icon: 'api',
      },
      { type: 'x', displayName: '', category: 'RULES' as const, icon: null },
    ];
    expect(decisionMaker('http', catalog)).toEqual({
      name: 'External system',
      color: 'var(--color-cat-trigger)',
      icon: 'api',
    });
    expect(decisionMaker('x', catalog)).toEqual({
      name: 'x',
      color: 'var(--color-cat-processor)',
      icon: 'shield_person',
    });
    expect(decisionMaker('y', undefined).color).toBe('var(--muted-foreground)');
    expect(categoryColor('ON_THIS_SERVER')).toBe('var(--success)');
  });
});
