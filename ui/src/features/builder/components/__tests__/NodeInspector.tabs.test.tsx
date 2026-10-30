// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi, beforeAll, afterAll } from 'vitest';
import { useState } from 'react';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { renderWithProviders } from '@/test/utils/testUtils';
import type { DraftEdge, DraftNode } from '../../types';
import { NodeInspector } from '../NodeInspector';

const server = setupServer(
  http.get('/api/plugins/:type', ({ params }) =>
    HttpResponse.json({
      timestamp: 't',
      status: 200,
      message: 'ok',
      path: '/',
      data: {
        type: params.type,
        category: 'PROCESSOR',
        description: null,
        usagePattern: null,
        uiDesign: null,
        outputPorts: [],
      },
    })
  )
);
beforeAll(() => server.listen());
afterAll(() => server.close());

const edges: DraftEdge[] = [
  { source: 'start', target: 'gate', sourcePort: null },
  { source: 'gate', target: 'ok', sourcePort: 'success' },
  { source: 'gate', target: 'fix', sourcePort: 'failure' },
  { source: 'start', target: 'other', sourcePort: null },
];

function Harness({ onRemoveEdge = vi.fn(), onAddEdge = vi.fn() }) {
  const [node, setNode] = useState<DraftNode>({
    nodeId: 'gate',
    type: 'X',
    config: { a: 1 },
  });
  return (
    <>
      <NodeInspector
        node={node}
        onRemove={vi.fn()}
        onChange={(config) => setNode({ ...node, config })}
        edges={edges}
        onRemoveEdge={onRemoveEdge}
        onAddEdge={onAddEdge}
      />
      <output data-testid="config">{JSON.stringify(node.config)}</output>
    </>
  );
}

describe('NodeInspector tabs', () => {
  it('lists the node’s edges by direction and port, and removes or adds one', async () => {
    const user = userEvent.setup();
    const onRemoveEdge = vi.fn();
    const onAddEdge = vi.fn();
    renderWithProviders(
      <Harness onRemoveEdge={onRemoveEdge} onAddEdge={onAddEdge} />
    );
    await user.click(screen.getByRole('tab', { name: 'Connections' }));

    expect(screen.getByRole('heading', { name: 'In 1' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Out 2' })).toBeInTheDocument();
    expect(screen.getByText('failure')).toBeInTheDocument();
    expect(screen.getByText('default')).toBeInTheDocument();
    expect(screen.queryByText('other')).not.toBeInTheDocument();

    await user.click(
      screen.getByRole('button', { name: 'Remove the edge gate → fix' })
    );
    expect(onRemoveEdge).toHaveBeenCalledWith(edges[2]);
    await user.click(
      screen.getByRole('button', { name: 'Add an edge from gate' })
    );
    expect(onAddEdge).toHaveBeenCalled();
  });

  it('says when a node has no edges', async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <NodeInspector
        node={{ nodeId: 'lone', type: 'X', config: {} }}
        onRemove={vi.fn()}
        onChange={vi.fn()}
      />
    );
    await user.click(screen.getByRole('tab', { name: 'Connections' }));
    expect(screen.getAllByText('None.')).toHaveLength(2);
  });

  it('edits the whole config as JSON', async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness />);
    await user.click(screen.getByRole('tab', { name: 'JSON' }));
    const box = screen.getByLabelText(/config, with its engine policy/);
    expect(box).toHaveValue('{\n  "a": 1\n}');
    await user.clear(box);
    await user.paste('{"b":2,"engine":{"onError":"CONTINUE"}}');
    expect(screen.getByTestId('config')).toHaveTextContent(
      '{"b":2,"engine":{"onError":"CONTINUE"}}'
    );
  });
});
