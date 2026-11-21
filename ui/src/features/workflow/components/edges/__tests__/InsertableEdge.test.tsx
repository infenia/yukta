// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { Position, type EdgeProps } from '@xyflow/react';
import { InsertableEdge } from '../InsertableEdge';
import { EdgeAdder } from '../../../lib/stepAdders';

// The label layer is a portal into the flow; here it is drawn in place.
vi.mock('@xyflow/react', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@xyflow/react')>()),
  EdgeLabelRenderer: ({ children }: { children: React.ReactNode }) => (
    <div data-testid="labels">{children}</div>
  ),
}));

function edge(over: Partial<EdgeProps> = {}) {
  return {
    id: 'e1',
    source: 'a',
    target: 'b',
    sourceX: 0,
    sourceY: 0,
    targetX: 200,
    targetY: 100,
    sourcePosition: Position.Right,
    targetPosition: Position.Left,
    data: { sourcePort: 'ok' },
    ...over,
  } as unknown as EdgeProps;
}

const draw = (props: EdgeProps, adder?: EdgeAdderFn) =>
  render(
    <EdgeAdder.Provider value={adder ?? null}>
      <svg>
        <InsertableEdge {...props} />
      </svg>
    </EdgeAdder.Provider>
  );
type EdgeAdderFn = NonNullable<React.ContextType<typeof EdgeAdder>>;

describe('InsertableEdge', () => {
  it('draws the edge, and what the editor gives at its middle, told which edge it is', () => {
    const adder = vi.fn(() => <button type="button">add</button>);
    const { container } = draw(edge(), adder);

    expect(container.querySelector('path')).not.toBeNull();
    expect(screen.getByRole('button', { name: 'add' })).toBeInTheDocument();
    expect(adder).toHaveBeenCalledWith({
      source: 'a',
      target: 'b',
      sourcePort: 'ok',
    });
  });

  it('says there is no port for an edge without one', () => {
    const adder = vi.fn(() => null);
    draw(edge({ data: undefined }), adder);
    draw(edge({ data: { sourcePort: null } }), adder);
    expect(adder).toHaveBeenNthCalledWith(1, {
      source: 'a',
      target: 'b',
      sourcePort: null,
    });
    expect(adder).toHaveBeenNthCalledWith(2, {
      source: 'a',
      target: 'b',
      sourcePort: null,
    });
  });

  it('draws only the edge when the editor gives nothing', () => {
    const { container } = draw(edge());
    expect(container.querySelector('path')).not.toBeNull();
    expect(screen.queryByTestId('labels')).toBeNull();
  });

  it('adds nothing to an edge that is not the workflow’s, one to or from a closed stage', () => {
    const adder = vi.fn(() => <button type="button">add</button>);
    draw(edge({ source: 'stage:build' }), adder);
    draw(edge({ target: 'stage:ship' }), adder);
    expect(adder).not.toHaveBeenCalled();
    expect(screen.queryByRole('button', { name: 'add' })).toBeNull();
  });
});
