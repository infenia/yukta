// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import {
  describe,
  it,
  expect,
  vi,
  beforeAll,
  afterAll,
  afterEach,
} from 'vitest';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { renderWithProviders } from '@/test/utils/testUtils';
import type { SessionDraft } from '../../types';
import { WorkflowEditorPage } from '../WorkflowEditorPage';

// The canvas is replaced by a list of the nodes and the edges it is given.
vi.mock('@/features/workflow/components/WorkflowCanvas', () => ({
  WorkflowCanvas: ({
    graph,
  }: {
    graph: {
      nodes: { nodeId: string; outputPorts: string[] }[];
      edges: { source: string; target: string }[];
    };
  }) => (
    <ul aria-label="Canvas">
      {graph.nodes.map((n) => (
        <li key={n.nodeId}>
          node {n.nodeId} ports {n.outputPorts.join(',')}
        </li>
      ))}
      {graph.edges.map((e) => (
        <li key={`${e.source}>${e.target}`}>
          edge {e.source}→{e.target}
        </li>
      ))}
    </ul>
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
      edges: [{ source: 'start', target: 'out', sourcePort: null }],
      assignments: [
        { supervisor: 'guard', nodes: ['out', 'group.*'] },
        { supervisor: 'watch' },
      ],
    },
  },
};

const saved: SessionDraft[] = [];
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
  ),
  http.post('/api/sessions/validate', () =>
    ok({ valid: true, message: null, outputPorts: { out: ['ok'] } })
  ),
  http.post('/api/sessions', async ({ request }) => {
    saved.push((await request.json()) as SessionDraft);
    return ok(null);
  })
);
beforeAll(() => server.listen());
afterEach(() => {
  sessionStorage.clear();
  server.resetHandlers();
  saved.length = 0;
  window.history.pushState({}, '', '/');
});
afterAll(() => server.close());

function renderEditor() {
  window.history.pushState({}, '', '/sessions/s1/workflow/wf/edit');
  return renderWithProviders(
    <Routes>
      <Route
        path="sessions/:sessionId/workflow/:workflowId/edit"
        element={<WorkflowEditorPage />}
      />
      <Route path="*" element={<p>elsewhere</p>} />
    </Routes>
  );
}

const rename = async (
  user: ReturnType<typeof userEvent.setup>,
  nodeId: string
) => {
  const field = await screen.findByLabelText('Node ID');
  await user.clear(field);
  await user.type(field, `${nodeId}{Enter}`);
};

describe('WorkflowEditorPage renaming a node', () => {
  it('follows the node through its edges, its ports, the supervisors and the selection', async () => {
    const user = userEvent.setup();
    renderEditor();
    await user.click(await screen.findByRole('button', { name: /^out/ }));
    // the ports of `out` come from the first check of the draft
    expect(
      await screen.findByText('node out ports ok', {}, { timeout: 3000 })
    ).toBeInTheDocument();

    await rename(user, 'done');

    const canvas = within(screen.getByRole('list', { name: 'Canvas' }));
    expect(canvas.getByText('node done ports ok')).toBeInTheDocument();
    expect(canvas.getByText('edge start→done')).toBeInTheDocument();
    expect(canvas.queryByText(/out/)).toBeNull();
    expect(
      await screen.findByRole('region', { name: 'Node done' })
    ).toBeInTheDocument();
    expect(
      within(screen.getByRole('list', { name: 'Nodes' })).getByText('done')
    ).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Save version' }));
    await screen.findByText('elsewhere');
    expect(saved[0].workflows.wf.assignments).toEqual([
      { supervisor: 'guard', nodes: ['done', 'group.*'] },
      { supervisor: 'watch' },
    ]);
    expect(saved[0].workflows.wf.nodes.map((n) => n.nodeId)).toEqual([
      'start',
      'done',
    ]);
  });

  it('keeps the node when the ID is taken, blank or has a space, and when it is unchanged', async () => {
    const user = userEvent.setup();
    renderEditor();
    await user.click(await screen.findByRole('button', { name: /^out/ }));

    await rename(user, 'start');
    expect(
      screen.getByText('Another node already has the ID start.')
    ).toBeInTheDocument();

    const field = screen.getByLabelText('Node ID');
    await user.clear(field);
    await user.type(field, '{Enter}');
    expect(
      screen.getByText('An ID cannot be blank or hold spaces.')
    ).toBeInTheDocument();

    await rename(user, 'a b');
    expect(
      screen.getByText('An ID cannot be blank or hold spaces.')
    ).toBeInTheDocument();

    // unchanged, apart from the spaces around it
    await rename(user, ' out ');
    expect(
      screen.queryByText('An ID cannot be blank or hold spaces.')
    ).toBeNull();
    expect(
      screen.getByRole('region', { name: 'Node out' })
    ).toBeInTheDocument();
    expect(screen.getByRole('list', { name: 'Canvas' })).toHaveTextContent(
      'edge start→out'
    );
  });
});
