// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useState } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { FindNodes } from '../FindNodes';
import type { WorkflowGraph } from '../../types/workflow';

const node = (nodeId: string, extra = {}) => ({
  nodeId,
  type: 'PROCESSOR',
  category: null,
  description: null,
  uiDesign: null,
  outputPorts: [],
  ...extra,
});
const graph = {
  workflowId: 'w',
  description: '',
  nodes: [
    node('fetch', { description: 'Download the archive' }),
    node('parse'),
    node('store', { type: 'SINK' }),
  ],
  edges: [{ source: 'fetch', target: 'parse', sourcePort: null }],
  topologicalOrder: [],
} as WorkflowGraph;

function Harness({ onPick }: { onPick: (nodeId: string) => void }) {
  const [query, setQuery] = useState('');
  return (
    <FindNodes graph={graph} query={query} onQuery={setQuery} onPick={onPick} />
  );
}

describe('FindNodes', () => {
  it('opens the field, lists matches and picks one', async () => {
    const user = userEvent.setup();
    const onPick = vi.fn();
    render(<Harness onPick={onPick} />);
    await user.click(screen.getByRole('button', { name: 'Find nodes' }));
    const input = screen.getByRole('searchbox', { name: 'Find nodes' });
    expect(screen.queryByRole('status')).toBeNull();

    await user.type(input, 'fetch -> parse');
    expect(screen.getByRole('status')).toHaveTextContent('2 matches');
    expect(
      screen.getByRole('list', { name: 'Matching nodes' })
    ).toHaveTextContent('on a matching edge');

    await user.clear(input);
    await user.type(input, 'archive');
    expect(screen.getByRole('status')).toHaveTextContent('1 match');
    await user.click(screen.getByRole('button', { name: /fetch/ }));
    expect(onPick).toHaveBeenCalledWith('fetch');
  });

  it('labels a match by its type when it has no description', async () => {
    const user = userEvent.setup();
    render(<Harness onPick={vi.fn()} />);
    await user.click(screen.getByRole('button', { name: 'Find nodes' }));
    await user.type(
      screen.getByRole('searchbox', { name: 'Find nodes' }),
      'sink'
    );
    expect(screen.getByRole('status')).toHaveTextContent('1 match');
    expect(screen.getByRole('button', { name: /store/ })).toHaveTextContent(
      'SINK'
    );
  });

  it('closes on Escape and on the close button, clearing the query', async () => {
    const user = userEvent.setup();
    render(<Harness onPick={vi.fn()} />);
    await user.click(screen.getByRole('button', { name: 'Find nodes' }));
    await user.type(screen.getByRole('searchbox', { name: 'Find nodes' }), 'x');
    await user.keyboard('{Escape}');
    expect(
      screen.getByRole('button', { name: 'Find nodes' })
    ).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Find nodes' }));
    await user.click(screen.getByRole('button', { name: 'Close find' }));
    expect(
      screen.getByRole('button', { name: 'Find nodes' })
    ).toBeInTheDocument();
  });
});
