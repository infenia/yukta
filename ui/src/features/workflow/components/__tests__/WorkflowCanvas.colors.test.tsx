// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { WorkflowCanvas } from '../WorkflowCanvas';
import { createStagedGraph } from '@/test/factories/workflowFactory';

const fills = () =>
  [...document.querySelectorAll<SVGElement>('.react-flow__minimap-node')].map(
    (el) => el.style.fill || el.getAttribute('fill')
  );

describe('WorkflowCanvas colors', () => {
  it('tints a colored group (closed box and open frame) with a dot, never on its border', () => {
    const graph = {
      ...createStagedGraph(),
      groupColors: { test: 'teal', lint: 'magenta' },
    };
    render(
      <WorkflowCanvas
        graph={graph}
        tasksByNode={{}}
        stageView={{ open: new Set(['lint']), onToggle: () => {} }}
      />
    );

    for (const id of ['stage-test', 'stage-frame-lint']) {
      const box = screen.getByTestId(id);
      expect(box.style.backgroundColor).toContain('color-mix');
      expect(box.style.borderColor).toBe('');
      expect(
        box.querySelector('[data-testid="stage-color-dot"]')
      ).not.toBeNull();
    }
    expect(screen.getByTestId('stage-test').style.backgroundColor).toContain(
      'var(--pal-teal)'
    );
  });

  it('paints colored nodes and groups on the minimap, the category accent otherwise', () => {
    const base = createStagedGraph();
    const graph = {
      ...base,
      nodes: base.nodes.map((n) =>
        n.nodeId === 'start' ? { ...n, color: 'sky' } : n
      ),
      groupColors: { test: 'stone' },
    };
    render(
      <WorkflowCanvas
        graph={graph}
        tasksByNode={{}}
        stageView={{ open: new Set(['lint']), onToggle: () => {} }}
      />
    );

    const all = fills().join(' ');
    expect(all).toContain('var(--pal-sky)');
    expect(all).toContain('var(--pal-stone)');
    expect(all).toContain('var(--color-cat-processor)');
  });
});
