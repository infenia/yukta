// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi, beforeAll, afterAll } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { renderWithProviders } from '@/test/utils/testUtils';
import type { SessionDraft } from '../../types';
import { WorkflowEditorPage } from '../WorkflowEditorPage';

// The canvas is replaced by a button that clicks a node the way the canvas does.
vi.mock('@/features/workflow/components/WorkflowCanvas', () => ({
  WorkflowCanvas: ({
    graph,
    onNodeClick,
  }: {
    graph: { nodes: unknown[] };
    onNodeClick: (nodeId: string) => void;
  }) => (
    <div data-testid="canvas" data-nodes={graph.nodes.length}>
      <button type="button" onClick={() => onNodeClick('out')}>
        click out
      </button>
    </div>
  ),
}));

const ok = (data: unknown) =>
  HttpResponse.json({
    timestamp: 't',
    status: 200,
    message: 'ok',
    data,
    path: '/',
  });
const session: SessionDraft = {
  sessionId: 's1',
  name: 'n',
  description: 'd',
  initiator: 'i',
  tags: {},
  projectPath: '/p',
  workflows: {
    wf: {
      workflowId: 'wf',
      description: 'w',
      nodes: [
        { nodeId: 'start', type: 'X', config: {} },
        { nodeId: 'out', type: 'X', config: {} },
      ],
      edges: [],
    },
  },
};
const server = setupServer(
  http.get('/api/sessions/:sid/config', () => ok(session)),
  http.get('/api/plugins', () => ok([{ type: 'X', category: 'PROCESSOR' }])),
  http.get('/api/plugins/:type', () =>
    ok({
      type: 'X',
      category: 'PROCESSOR',
      description: null,
      usagePattern: null,
      uiDesign: null,
      outputPorts: [],
    })
  )
);
beforeAll(() => server.listen());
afterAll(() => server.close());

describe('WorkflowEditorPage selecting on the canvas', () => {
  it('selects a clicked node, deselects on Escape and deletes on Delete, but not while typing', async () => {
    const user = userEvent.setup();
    window.history.pushState({}, '', '/sessions/s1/workflow/wf/edit');
    renderWithProviders(
      <Routes>
        <Route
          path="sessions/:sessionId/workflow/:workflowId/edit"
          element={<WorkflowEditorPage />}
        />
      </Routes>
    );
    await user.click(await screen.findByRole('button', { name: 'click out' }));
    expect(
      screen.queryByText('Select a node to configure it.')
    ).not.toBeInTheDocument();

    await user.keyboard('{Escape}');
    expect(
      screen.getByText('Select a node to configure it.')
    ).toBeInTheDocument();

    // with nothing selected, Delete and Backspace remove nothing, other keys do nothing
    await user.keyboard('{Delete}{Backspace}a');
    expect(screen.getByTestId('canvas')).toHaveAttribute('data-nodes', '2');

    // typing in a field must not delete the node
    await user.click(screen.getByRole('button', { name: 'click out' }));
    await user.click(screen.getByLabelText('Description'));
    await user.keyboard('{Backspace}');
    expect(screen.getByTestId('canvas')).toHaveAttribute('data-nodes', '2');

    await user.click(screen.getByRole('button', { name: 'click out' }));
    await user.keyboard('{Delete}');
    expect(screen.getByTestId('canvas')).toHaveAttribute('data-nodes', '1');
    expect(
      screen.getByText('Select a node to configure it.')
    ).toBeInTheDocument();
  });
});
