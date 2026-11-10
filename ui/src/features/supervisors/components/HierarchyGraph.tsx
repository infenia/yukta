// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { CSSProperties } from 'react';
import { Background, ReactFlow, type Edge, type Node } from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import type { SupervisorView } from '../types';

const COLUMN = 200;
const ROW = 120;

/**
 * The reporting chain around one supervisor: its parents above, the supervisor in the middle,
 * the supervisors that report to it below. Read only; the links beside it open each one.
 */
export function HierarchyGraph({
  supervisor,
  all,
}: {
  supervisor: SupervisorView;
  all: SupervisorView[];
}) {
  const parents = supervisor.parents ?? [];
  const children = all.filter((s) => (s.parents ?? []).includes(supervisor.id));
  const { nodes, edges } = (() => {
    const ns: Node[] = [];
    const es: Edge[] = [];
    const place = (ids: string[], y: number) =>
      ids.forEach((id, i) => {
        const x = (i - (ids.length - 1) / 2) * COLUMN;
        ns.push({
          id,
          position: { x, y },
          data: { label: id },
          style: nodeStyle(id === supervisor.id),
        });
      });
    place(parents, 0);
    place([supervisor.id], ROW);
    place(
      children.map((c) => c.id),
      2 * ROW
    );
    for (const p of parents)
      es.push({
        id: `${p}->${supervisor.id}`,
        source: p,
        target: supervisor.id,
      });
    for (const c of children)
      es.push({
        id: `${supervisor.id}->${c.id}`,
        source: supervisor.id,
        target: c.id,
      });
    return { nodes: ns, edges: es };
  })();

  return (
    <div
      className="border-border bg-card shadow-card h-64 overflow-hidden rounded-xl border"
      aria-label="Reporting chain graph"
      role="img"
    >
      <ReactFlow
        nodes={nodes}
        edges={edges}
        fitView
        fitViewOptions={{ padding: 0.3 }}
        nodesDraggable={false}
        nodesConnectable={false}
        elementsSelectable={false}
        proOptions={{ hideAttribution: true }}
      >
        <Background gap={16} size={1} />
      </ReactFlow>
    </div>
  );
}

function nodeStyle(current: boolean): CSSProperties {
  return {
    background: current ? 'var(--color-accent)' : 'var(--color-card)',
    color: 'var(--color-on-surface)',
    border: `1px solid ${current ? 'var(--color-primary)' : 'var(--color-border)'}`,
    borderRadius: 12,
    fontSize: 13,
    padding: '6px 12px',
  };
}
