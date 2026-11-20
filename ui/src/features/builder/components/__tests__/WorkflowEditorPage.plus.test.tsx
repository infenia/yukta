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
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { renderWithProviders } from '@/test/utils/testUtils';
import type { SessionDraft } from '../../types';
import { WorkflowEditorPage } from '../WorkflowEditorPage';

// The canvas is replaced by one line per node and per edge, each with what the editor draws
// on it (the "+" buttons), the way the real canvas would.
vi.mock('@/features/workflow/components/WorkflowCanvas', async () => {
  const { useContext } = await import('react');
  const { NodeAdder, EdgeAdder } =
    await import('@/features/workflow/lib/stepAdders');
  return {
    WorkflowCanvas: ({
      graph,
    }: {
      graph: {
        nodes: { nodeId: string; category: string | null }[];
        edges: { source: string; target: string; sourcePort: string | null }[];
      };
    }) => {
      const nodeAdder = useContext(NodeAdder);
      const edgeAdder = useContext(EdgeAdder);
      return (
        <ul aria-label="Canvas">
          {graph.nodes.map((n) => (
            <li key={n.nodeId}>
              node {n.nodeId}
              {n.category !== 'TERMINAL' && nodeAdder?.(n as never)}
            </li>
          ))}
          {graph.edges.map((e) => (
            <li key={`${e.source}>${e.target}`}>
              edge {e.source}→{e.target}
              {edgeAdder?.(e)}
            </li>
          ))}
        </ul>
      );
    },
  };
});

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
        { nodeId: 'start', type: 'API_TRIGGER', config: {} },
        { nodeId: 'out', type: 'CONSOLE_TERMINAL', config: {} },
      ],
      edges: [{ source: 'start', target: 'out', sourcePort: 'ok' }],
    },
  },
};
const saved: SessionDraft[] = [];
const server = setupServer(
  http.get('/api/sessions/:sid/config', () => ok(session)),
  http.get('/api/plugins', () =>
    ok([
      { type: 'API_TRIGGER', category: 'TRIGGER' },
      { type: 'BRANCH', category: 'PROCESSOR' },
      { type: 'CONSOLE_TERMINAL', category: 'TERMINAL' },
    ])
  ),
  http.get('/api/plugins/:type', ({ params }) =>
    ok({
      type: params.type,
      category: 'PROCESSOR',
      description: null,
      usagePattern: null,
      uiDesign: null,
      outputPorts: [],
    })
  ),
  http.post('/api/sessions/validate', () =>
    ok({ valid: true, message: null, outputPorts: {} })
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
const canvas = () => within(screen.getByRole('list', { name: 'Canvas' }));

describe('WorkflowEditorPage the + on the canvas', () => {
  it('adds a step after the node it is under, and not under a terminal', async () => {
    const user = userEvent.setup();
    renderEditor();
    await screen.findByRole('region', { name: 'Node start' });
    // once the plugins are known, `out` is a terminal and has none
    await waitFor(() =>
      expect(
        screen.queryByRole('button', { name: 'Add a step after out' })
      ).toBeNull()
    );

    await user.click(
      screen.getByRole('button', { name: 'Add a step after start' })
    );
    const processors = await screen.findByRole('region', {
      name: 'Processors',
    });
    expect(
      screen.getByRole('region', { name: 'Terminals' })
    ).toBeInTheDocument();
    // a trigger starts a workflow, so none is offered after a node
    expect(screen.queryByRole('region', { name: 'Triggers' })).toBeNull();
    await user.click(
      within(processors).getByRole('button', { name: /Branch/ })
    );

    expect(canvas().getByText(/node branch-1/)).toBeInTheDocument();
    expect(canvas().getByText(/edge start→branch-1/)).toBeInTheDocument();
    // the edge that was there stays
    expect(canvas().getByText(/edge start→out/)).toBeInTheDocument();
    expect(
      await screen.findByRole('region', { name: 'Node branch-1' })
    ).toBeInTheDocument();
  });

  it('adds a step after a node that is not the selected one', async () => {
    const user = userEvent.setup();
    renderEditor();
    await screen.findByRole('region', { name: 'Node start' });
    await user.click(screen.getByRole('button', { name: /^out/ }));
    await screen.findByRole('region', { name: 'Node out' });

    await user.click(
      screen.getByRole('button', { name: 'Add a step after start' })
    );
    await user.click(
      within(
        await screen.findByRole('region', { name: 'Processors' })
      ).getByRole('button', { name: /Branch/ })
    );
    // connected after `start`, though `out` (a terminal) was the selected node
    expect(canvas().getByText(/edge start→branch-1/)).toBeInTheDocument();
  });

  it('puts a step on an edge, so the edge goes through it, keeping its port', async () => {
    const user = userEvent.setup();
    renderEditor();
    await screen.findByRole('region', { name: 'Node start' });

    await user.click(
      screen.getByRole('button', { name: 'Add a step between start and out' })
    );
    const picker = await screen.findByRole('region', { name: 'Processors' });
    // a step between two nodes needs an output of its own: no terminal, no trigger
    expect(screen.queryByRole('region', { name: 'Terminals' })).toBeNull();
    expect(screen.queryByRole('region', { name: 'Triggers' })).toBeNull();
    await user.click(within(picker).getByRole('button', { name: /Branch/ }));

    expect(canvas().queryByText(/edge start→out/)).toBeNull();
    expect(canvas().getByText(/edge start→branch-1/)).toBeInTheDocument();
    expect(canvas().getByText(/edge branch-1→out/)).toBeInTheDocument();
    expect(
      await screen.findByRole('region', { name: 'Node branch-1' })
    ).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Save version' }));
    await screen.findByText('elsewhere');
    expect(saved[0].workflows.wf.edges).toEqual([
      { source: 'start', target: 'branch-1', sourcePort: 'ok' },
      { source: 'branch-1', target: 'out', sourcePort: null },
    ]);
  });

  it('puts a step on an edge without a port, matching it as it is', async () => {
    const user = userEvent.setup();
    server.use(
      http.get('/api/sessions/:sid/config', () =>
        ok({
          ...session,
          workflows: {
            wf: {
              ...session.workflows.wf,
              edges: [{ source: 'start', target: 'out', sourcePort: null }],
            },
          },
        })
      )
    );
    renderEditor();
    await screen.findByRole('region', { name: 'Node start' });

    await user.click(
      screen.getByRole('button', { name: 'Add a step between start and out' })
    );
    const picker = await screen.findByRole('region', { name: 'Processors' });
    await user.click(within(picker).getByRole('button', { name: /Branch/ }));

    expect(canvas().queryByText(/edge start→out/)).toBeNull();
    expect(canvas().getByText(/edge start→branch-1/)).toBeInTheDocument();
    expect(canvas().getByText(/edge branch-1→out/)).toBeInTheDocument();
  });
});
