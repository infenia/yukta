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
  });
  return (
    <>
      <NodeInspector
        node={node}
        onRemove={vi.fn()}
        onChange={vi.fn()}
        onGroupChange={vi.fn()}
        onDescriptionChange={(description) => setNode({ ...node, description })}
      />
      <output data-testid="out">
        {JSON.stringify(node.description ?? null)}
      </output>
    </>
  );
}

describe('NodeInspector description', () => {
  it('sets the description and clears it when blank', async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness />);
    const field = await screen.findByLabelText('Node description');

    await user.type(field, 'Runs lint');
    expect(screen.getByTestId('out')).toHaveTextContent('"Runs lint"');

    await user.clear(field);
    expect(screen.getByTestId('out')).toHaveTextContent('null');
  });
});
