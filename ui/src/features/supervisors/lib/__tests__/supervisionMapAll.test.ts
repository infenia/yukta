// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, expect, it } from 'vitest';
import {
  MAX_MAP_WORKFLOWS,
  supervisionMapAll,
  type MapAllInput,
} from '../supervisionMapAll';

const maker = (type: string) => ({
  name: type === 'rules' ? 'Rules' : 'External',
  icon: 'rule',
  color: 'red',
});
const base: MapAllInput = {
  supervisors: [
    { id: 'lead', type: 'rules', dryRun: false },
    { id: 'guard', type: 'http', dryRun: true, parents: ['lead'] },
  ],
  maker,
  attachments: [],
  unowned: [],
  opened: {},
};
const run = (input: Partial<MapAllInput>) =>
  supervisionMapAll({ ...base, ...input });
const byId = (g: ReturnType<typeof run>) =>
  Object.fromEntries(g.nodes.map((n) => [n.id, n]));

describe('supervisionMapAll', () => {
  it('draws one decision maker per type, the supervisors and who reports to whom', () => {
    const g = run({});
    const n = byId(g);
    expect(n['maker:rules'].label).toBe('Rules');
    expect(n['supervisor:guard'].dry).toBe(true);
    expect(n['supervisor:guard'].sub).toBe('Dry run');
    expect(n['supervisor:lead'].sub).toBeUndefined();
    expect(n['maker:rules'].x).toBeLessThan(n['supervisor:lead'].x);
    expect(g.edges.map((e) => e.id)).toEqual(
      expect.arrayContaining([
        'maker:rules->supervisor:lead',
        'supervisor:lead->supervisor:guard',
      ])
    );
    // two supervisors of one type share one maker box
    expect(
      run({
        supervisors: [
          { id: 'a', type: 'rules', dryRun: false },
          { id: 'b', type: 'rules', dryRun: false },
        ],
      }).nodes.filter((x) => x.kind === 'maker')
    ).toHaveLength(1);
  });

  it('draws a workflow once however many supervisors own it, dashed when off', () => {
    const g = run({
      attachments: [
        { supervisorId: 'lead', scope: 'workflow:s/w', enabled: true },
        {
          supervisorId: 'guard',
          scope: 'workflow:s/w//deploy.*',
          enabled: false,
        },
        { supervisorId: 'guard', scope: 'run:s/w', enabled: false },
      ],
    });
    expect(g.nodes.filter((n) => n.kind === 'workflow')).toHaveLength(1);
    const edge = (supervisor: string) =>
      g.edges.find((e) => e.id === `supervisor:${supervisor}->workflow:s/w`);
    expect(edge('lead')?.dashed).toBe(false);
    expect(edge('guard')?.dashed).toBe(true);
    const w = byId(g)['workflow:s/w'];
    expect(w.toggle).toBe('s/w');
    expect(w.href).toBe('/sessions/s/workflow/w');
  });

  it('draws a live assignment solid even when another one of the same supervisor is off', () => {
    const g = run({
      attachments: [
        { supervisorId: 'lead', scope: 'workflow:s/w//a', enabled: false },
        { supervisorId: 'lead', scope: 'workflow:s/w//b', enabled: true },
      ],
    });
    expect(g.edges.find((e) => e.target === 'workflow:s/w')?.dashed).toBe(
      false
    );
  });

  it('keeps a workflow with no session from opening, and draws other targets', () => {
    const g = run({
      attachments: [
        { supervisorId: 'lead', scope: 'workflow:w', enabled: true },
        { supervisorId: 'lead', scope: 'server', enabled: true },
      ],
    });
    const n = byId(g);
    expect(n['workflow:/w'].toggle).toBeUndefined();
    expect(n['workflow:/w'].href).toBeUndefined();
    expect(n['workflow:/w'].sub).toBeUndefined();
    expect(n['target:lead:server'].label).toBe('The server');
  });

  it('folds the workflows past the cap into one box', () => {
    const attachments = Array.from(
      { length: MAX_MAP_WORKFLOWS + 3 },
      (_, i) => ({
        supervisorId: 'lead',
        scope: `workflow:s/w${i}`,
        enabled: true,
      })
    );
    const g = run({ attachments });
    expect(g.hidden).toBe(3);
    expect(g.nodes.filter((n) => n.kind === 'workflow')).toHaveLength(
      MAX_MAP_WORKFLOWS
    );
    expect(byId(g)['more'].label).toBe('+3 more workflows');
  });

  it('shows the nodes of an opened workflow with their owners, folding a long list', () => {
    const owners = Object.fromEntries(
      Array.from({ length: 12 }, (_, i) => [
        `n${i}`,
        i === 0 ? 'default' : 'lead',
      ])
    );
    const g = run({
      attachments: [
        { supervisorId: 'lead', scope: 'workflow:s/w', enabled: true },
      ],
      opened: { 's/w': owners },
    });
    const n = byId(g);
    expect(n['workflow:s/w#n0'].sub).toBe('Direct control');
    expect(n['workflow:s/w#n1'].sub).toBe('lead');
    expect(n['workflow:s/w#more'].label).toBe('+2 nodes');
    // a short list has no fold box
    expect(
      byId(
        run({
          attachments: [
            { supervisorId: 'lead', scope: 'workflow:s/w', enabled: true },
          ],
          opened: { 's/w': { a: 'lead' } },
        })
      )['workflow:s/w#more']
    ).toBeUndefined();
  });

  it('draws a link named twice once', () => {
    const g = run({
      supervisors: [
        { id: 'lead', type: 'rules', dryRun: false },
        { id: 'guard', type: 'http', dryRun: true, parents: ['lead', 'lead'] },
      ],
    });
    expect(
      g.edges.filter((e) => e.id === 'supervisor:lead->supervisor:guard')
    ).toHaveLength(1);
  });

  it('draws Direct control with the workflows nobody owns', () => {
    const g = run({
      unowned: [{ sessionId: 's', workflowId: 'w' }],
    });
    const n = byId(g);
    expect(n['direct'].sub).toBe('1 workflow');
    expect(g.edges.map((e) => e.id)).toContain('direct->workflow:s/w');
    expect(
      byId(
        run({
          unowned: [
            { sessionId: 's', workflowId: 'a' },
            { sessionId: 's', workflowId: 'b' },
          ],
        })
      )['direct'].sub
    ).toBe('2 workflows');
    expect(byId(run({}))['direct']).toBeUndefined();
  });
});
