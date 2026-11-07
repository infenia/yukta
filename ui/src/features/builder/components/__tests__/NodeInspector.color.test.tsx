// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi } from 'vitest';
import { useState } from 'react';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '@/test/utils/testUtils';
import type { DraftNode } from '../../types';
import { NodeInspector } from '../NodeInspector';

function Harness() {
  const [node, setNode] = useState<DraftNode>({
    nodeId: 'n1',
    type: 'PLAIN',
    config: {},
    color: 'teal',
  });
  const [groupColor, setGroupColor] = useState<string | null>(null);
  return (
    <>
      <NodeInspector
        node={node}
        onRemove={vi.fn()}
        onChange={vi.fn()}
        onGroupChange={vi.fn()}
        groupColor={groupColor}
        onColorChange={(color) => setNode({ ...node, color })}
        onGroupColorChange={setGroupColor}
      />
      <output data-testid="out">
        {JSON.stringify({ color: node.color, groupColor })}
      </output>
    </>
  );
}

describe('NodeInspector colors', () => {
  it('sets and clears the node color and the stage color', async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness />);
    const out = () => JSON.parse(screen.getByTestId('out').textContent ?? '');

    const node = await screen.findByRole('radiogroup', { name: 'Node color' });
    const stage = screen.getByRole('radiogroup', { name: 'Stage color' });
    expect(node.querySelectorAll('[role="radio"]')).toHaveLength(6);
    expect(node.querySelector('[aria-label="teal"]')).toHaveAttribute(
      'aria-checked',
      'true'
    );

    await user.click(node.querySelector('[aria-label="violet"]') as Element);
    await user.click(stage.querySelector('[aria-label="sky"]') as Element);
    expect(out()).toEqual({ color: 'violet', groupColor: 'sky' });

    await user.click(node.querySelector('[aria-label="none"]') as Element);
    await user.click(stage.querySelector('[aria-label="none"]') as Element);
    expect(out()).toEqual({ color: null, groupColor: null });
  });
});
