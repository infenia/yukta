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

// The canvas is replaced by buttons that draw edges the way a drag would.
vi.mock('@/features/workflow/components/WorkflowCanvas', () => ({
  WorkflowCanvas: ({
    graph,
    onConnect,
  }: {
    graph: { edges: unknown[] };
    onConnect: (source: string, target: string, port: string | null) => void;
  }) => (
    <div data-testid="canvas" data-edges={graph.edges.length}>
      <button type="button" onClick={() => onConnect('start', 'out', 'ok')}>
        drag start.ok to out
      </button>
      <button type="button" onClick={() => onConnect('out', 'out', null)}>
        drag out to itself
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

describe('WorkflowEditorPage drawing edges', () => {
  it('adds an edge drawn on the canvas, once, and refuses a loop', async () => {
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
    await user.click(
      await screen.findByRole('button', { name: 'drag start.ok to out' })
    );
    expect(screen.getByTestId('canvas')).toHaveAttribute('data-edges', '1');
    await user.click(screen.getByRole('tab', { name: /Edges/ }));
    expect(screen.getByText(/start/, { selector: 'li *' })).toBeInTheDocument();

    await user.click(
      screen.getByRole('button', { name: 'drag start.ok to out' })
    );
    expect(screen.getByText('That edge already exists.')).toBeInTheDocument();
    await user.click(
      screen.getByRole('button', { name: 'drag out to itself' })
    );
    expect(
      screen.getByText('An edge cannot connect a node to itself.')
    ).toBeInTheDocument();
    expect(screen.getByTestId('canvas')).toHaveAttribute('data-edges', '1');
  });
});
