// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, expect, it } from 'vitest';
import { parseScope } from '../ownership';
import { supervisionGraph } from '../supervisionGraph';

const base = {
  supervisor: {
    id: 'guard',
    parents: ['lead'],
    delegation: { APPROVAL: { mode: 'ALL' } },
  },
  all: [
    { id: 'guard', parents: ['lead'] },
    { id: 'lead' },
    { id: 'helper', parents: ['guard'] },
  ],
  maker: { name: 'Rules' },
};
const t = (s: string) => parseScope(s);
const byId = (g: ReturnType<typeof supervisionGraph>) =>
  Object.fromEntries(g.nodes.map((n) => [n.id, n]));

describe('supervisionGraph', () => {
  it('lays the maker and the parents left of the supervisor, its children and workflows right', () => {
    const g = supervisionGraph({
      ...base,
      targets: [t('workflow:s/w//deploy.test,smoke')],
    });
    const n = byId(g);
    expect(n['maker'].x).toBeLessThan(n['self'].x);
    expect(n['parent:lead'].x).toBeLessThan(n['self'].x);
    expect(n['child:helper'].x).toBeGreaterThan(n['self'].x);
    expect(n['workflow:s/w'].x).toBeGreaterThan(n['self'].x);
    expect(g.edges.find((e) => e.source === 'parent:lead')?.label).toBe('all');
    expect(n['child:helper'].supervisor).toBe('helper');
  });

  it('links a node to its drawer tab and a selector to the workflow', () => {
    const g = supervisionGraph({
      ...base,
      targets: [t('workflow:s/w//deploy.test,build.*')],
    });
    const n = byId(g);
    expect(n['workflow:s/w#deploy.test'].href).toBe(
      '/sessions/s/workflow/w?node=deploy.test&tab=supervisor'
    );
    expect(n['workflow:s/w#build.*'].href).toBe('/sessions/s/workflow/w');
  });

  it('merges nodes from the ownership map, and folds more than five into one box', () => {
    const many = ['a', 'b', 'c', 'd', 'e', 'f'];
    const g = supervisionGraph({
      ...base,
      targets: [t('workflow:s/w')],
      ownedNodes: { 's/w': many },
    });
    const n = byId(g);
    expect(n['workflow:s/w#nodes'].label).toBe('6 nodes');
    expect(g.nodes.filter((x) => x.kind === 'nodes')).toHaveLength(1);
  });

  it('draws six workflows, then "+N more", and keeps the server and global as boxes', () => {
    const targets = Array.from({ length: 8 }, (_, i) => t(`workflow:s/w${i}`));
    const g = supervisionGraph({
      ...base,
      targets: [...targets, t('global'), t('server')],
    });
    expect(g.nodes.filter((x) => x.kind === 'workflow')).toHaveLength(6);
    expect(byId(g)['more'].label).toBe('+2 more');
    expect(g.hidden).toBe(2);
    expect(
      g.nodes.filter((x) => x.kind === 'target').map((x) => x.label)
    ).toEqual(['Everything (observes only)', 'The server']);
  });
});
