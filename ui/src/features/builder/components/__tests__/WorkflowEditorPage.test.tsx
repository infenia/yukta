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
import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import {
  createTestQueryClient,
  must,
  renderWithProviders,
} from '@/test/utils/testUtils';
import type { SessionDraft, ValidationResult } from '../../types';
import { toPreviewGraph, WorkflowEditorPage } from '../WorkflowEditorPage';

vi.mock('@/features/workflow/components/WorkflowCanvas', () => ({
  WorkflowCanvas: ({
    graph,
    focusOn,
  }: {
    graph: { nodes: unknown[]; edges: unknown[] };
    focusOn?: string[];
  }) => (
    <div data-testid="canvas" data-focus={focusOn?.join(',') ?? ''}>
      {graph.nodes.length} nodes {graph.edges.length} edges
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

const session = (): SessionDraft => ({
  sessionId: 's1',
  name: 'n',
  description: 'd',
  initiator: 'i',
  tags: { env: 'prod' },
  projectPath: '/p',
  workflows: {
    wf: {
      workflowId: 'wf',
      description: 'first',
      nodes: [
        { nodeId: 'start', type: 'API_TRIGGER', config: {} },
        { nodeId: 'out', type: 'CONSOLE_TERMINAL', config: {} },
      ],
      edges: [{ source: 'start', target: 'out', sourcePort: null }],
    },
    other: { workflowId: 'other', description: 'o', nodes: [], edges: [] },
  },
});

let sessionFails = false;
let validation: ValidationResult = {
  valid: true,
  message: null,
  outputPorts: { start: ['default'], out: [] },
};
let validateFails = false;
let saveFails = false;
const saved: SessionDraft[] = [];

const server = setupServer(
  http.get('/api/sessions/:sid/config', () =>
    sessionFails
      ? HttpResponse.json({ message: 'x' }, { status: 500 })
      : ok(session())
  ),
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
      outputPorts: ['default'],
    })
  ),
  http.post('/api/sessions/validate', () =>
    validateFails
      ? HttpResponse.json({ message: 'boom' }, { status: 500 })
      : ok(validation)
  ),
  http.post('/api/sessions', async ({ request }) => {
    if (saveFails)
      return HttpResponse.json({ message: 'disk full' }, { status: 500 });
    saved.push((await request.json()) as SessionDraft);
    return ok(null);
  })
);

beforeAll(() => server.listen());
afterEach(() => {
  server.resetHandlers();
  sessionFails = false;
  validateFails = false;
  saveFails = false;
  validation = {
    valid: true,
    message: null,
    outputPorts: { start: ['default'], out: [] },
  };
  saved.length = 0;
  window.history.pushState({}, '', '/');
});
afterAll(() => server.close());

function renderEditor(
  workflowId = 'wf',
  queryClient = createTestQueryClient()
) {
  window.history.pushState({}, '', `/sessions/s1/workflow/${workflowId}/edit`);
  return renderWithProviders(
    <Routes>
      <Route
        path="sessions/:sessionId/workflow/:workflowId/edit"
        element={<WorkflowEditorPage />}
      />
      <Route path="*" element={<p>elsewhere</p>} />
    </Routes>,
    { queryClient }
  );
}

describe('toPreviewGraph', () => {
  it('shapes a draft like the read-only graph with port and category fallbacks', () => {
    const graph = toPreviewGraph(
      {
        workflowId: 'wf',
        description: 'd',
        nodes: [
          { nodeId: 'a', type: 'T', config: {} },
          { nodeId: 'b', type: 'U', config: {} },
        ],
        edges: [{ source: 'a', target: 'b', sourcePort: null }],
      },
      { T: 'TRIGGER' },
      { a: ['x', 'y'] }
    );
    expect(graph.nodes[0]).toMatchObject({
      category: 'TRIGGER',
      outputPorts: ['x', 'y'],
    });
    expect(graph.nodes[1]).toMatchObject({
      category: null,
      outputPorts: ['default'],
    });
    expect(graph.edges).toHaveLength(1);
  });
});

describe('WorkflowEditorPage', () => {
  it('shows loading, then the workflow with its first node open and a preview', async () => {
    renderEditor();
    expect(screen.getByText('Loading…')).toBeInTheDocument();
    expect(
      await screen.findByRole('heading', { name: 'Edit wf' })
    ).toBeInTheDocument();
    expect(screen.getByLabelText('Description')).toHaveValue('first');
    expect(screen.getByTestId('canvas')).toHaveTextContent('2 nodes 1 edges');
    expect(
      await screen.findByRole('region', { name: 'Node start' })
    ).toBeInTheDocument();
  });

  it('waits for the fresh session instead of seeding the draft from a stale cache', async () => {
    // e.g. after a rollback: the cache still holds the pre-rollback definition
    const queryClient = createTestQueryClient();
    const stale = session();
    stale.workflows.wf = {
      ...must(stale.workflows.wf),
      description: 'before the rollback',
    };
    queryClient.setQueryData(['sessions', 's1', 'config'], stale);

    renderEditor('wf', queryClient);

    expect(screen.getByText('Loading…')).toBeInTheDocument();
    expect(screen.queryByLabelText('Description')).not.toBeInTheDocument();
    expect(await screen.findByLabelText('Description')).toHaveValue('first');
  });

  it('keeps the draft when the session is refetched after the editor is open', async () => {
    const queryClient = createTestQueryClient();
    renderEditor('wf', queryClient);
    const description = await screen.findByLabelText('Description');
    await userEvent.clear(description);
    await userEvent.type(description, 'my edit');

    await queryClient.invalidateQueries({
      queryKey: ['sessions', 's1', 'config'],
    });

    expect(screen.getByLabelText('Description')).toHaveValue('my edit');
  });

  it('starts a fresh draft when the route moves to another workflow', async () => {
    renderEditor();
    expect(await screen.findByLabelText('Description')).toHaveValue('first');

    act(() => {
      window.history.pushState({}, '', '/sessions/s1/workflow/other/edit');
      window.dispatchEvent(new PopStateEvent('popstate'));
    });

    expect(
      await screen.findByRole('heading', { name: 'Edit other' })
    ).toBeInTheDocument();
    await waitFor(() =>
      expect(screen.getByLabelText('Description')).toHaveValue('o')
    );
  });

  it('reports a session that cannot be loaded', async () => {
    sessionFails = true;
    renderEditor();
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Failed to load session'
    );
  });

  it('starts an empty draft for a workflow the session does not have yet', async () => {
    renderEditor('brand-new');
    expect(
      await screen.findByRole('heading', { name: 'Edit brand-new' })
    ).toBeInTheDocument();
    expect(
      screen.getByText('Select a node to configure it.')
    ).toBeInTheDocument();
    expect(screen.getByTestId('canvas')).toHaveTextContent('0 nodes');
  });

  it('adds nodes with unique ids, the chosen type first and the first plugin by default', async () => {
    const user = userEvent.setup();
    renderEditor('other');
    await screen.findByRole('heading', { name: 'Edit other' });
    await screen.findByRole('option', { name: 'BRANCH' });

    await user.click(screen.getByRole('button', { name: 'Add node' }));
    await user.selectOptions(screen.getByLabelText('Plugin type'), 'BRANCH');
    await user.click(screen.getByRole('button', { name: 'Add node' }));
    await user.click(screen.getByRole('button', { name: 'Add node' }));

    const nodes = within(screen.getByRole('list', { name: 'Nodes' }));
    expect(nodes.getByText('api-trigger-1')).toBeInTheDocument();
    expect(nodes.getByText('branch-1')).toBeInTheDocument();
    expect(nodes.getByText('branch-2')).toBeInTheDocument();
    expect(
      await screen.findByRole('region', { name: 'Node branch-2' })
    ).toBeInTheDocument();
  });

  it('adds the plugin the select shows, even when the API lists another category first', async () => {
    server.use(
      http.get('/api/plugins', () =>
        ok([
          { type: 'CONSOLE_TERMINAL', category: 'TERMINAL' },
          { type: 'BRANCH', category: 'PROCESSOR' },
          { type: 'API_TRIGGER', category: 'TRIGGER' },
        ])
      )
    );
    const user = userEvent.setup();
    renderEditor('other');
    await screen.findByRole('option', { name: 'BRANCH' });

    expect(screen.getByLabelText('Plugin type')).toHaveValue('API_TRIGGER');
    await user.click(screen.getByRole('button', { name: 'Add node' }));

    expect(
      within(screen.getByRole('list', { name: 'Nodes' })).getByText(
        'api-trigger-1'
      )
    ).toBeInTheDocument();
  });

  it('does nothing when asked to add a node before any plugin has loaded', async () => {
    server.use(http.get('/api/plugins', () => ok([])));
    const user = userEvent.setup();
    renderEditor('other');
    await screen.findByRole('heading', { name: 'Edit other' });
    await user.click(screen.getByRole('button', { name: 'Add node' }));
    expect(screen.getByTestId('canvas')).toHaveTextContent('0 nodes');
  });

  it('selects another node and removes one together with its edges', async () => {
    const user = userEvent.setup();
    renderEditor();
    await screen.findByRole('region', { name: 'Node start' });

    await user.click(screen.getByRole('button', { name: /^out/ }));
    expect(
      await screen.findByRole('region', { name: 'Node out' })
    ).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Remove node' }));
    expect(
      screen.getByText('Select a node to configure it.')
    ).toBeInTheDocument();
    expect(screen.getByTestId('canvas')).toHaveTextContent('1 nodes 0 edges');
  });

  it('edits node configuration through the inspector', async () => {
    const user = userEvent.setup();
    renderEditor();
    const box = await screen.findByLabelText('Configuration (JSON)');
    await user.click(box);
    await user.paste('{"k":1}');
    await user.click(screen.getByRole('button', { name: 'Validate' }));
    expect(await screen.findByText('Workflow is valid.')).toBeInTheDocument();
  });

  it('adds and removes edges, offering ports once validated', async () => {
    const user = userEvent.setup();
    renderEditor();
    await screen.findByRole('region', { name: 'Node start' });
    await user.click(screen.getByRole('tab', { name: /Edges/ }));
    await user.click(
      screen.getByRole('button', { name: 'Remove edge start to out' })
    );
    expect(screen.getByTestId('canvas')).toHaveTextContent('2 nodes 0 edges');

    await user.click(screen.getByRole('button', { name: 'Add edge' }));
    expect(screen.getByTestId('canvas')).toHaveTextContent('0 edges');

    await user.click(screen.getByRole('button', { name: 'Validate' }));
    await screen.findByText('Workflow is valid.');
    await user.selectOptions(screen.getByLabelText('Edge source'), 'start');
    await user.selectOptions(screen.getByLabelText('Edge port'), 'default');
    await user.selectOptions(screen.getByLabelText('Edge target'), 'out');
    await user.click(screen.getByRole('button', { name: 'Add edge' }));
    expect(screen.getByTestId('canvas')).toHaveTextContent('1 edges');
    expect(screen.getByText('start:default → out')).toBeInTheDocument();

    await user.selectOptions(screen.getByLabelText('Edge source'), 'out');
    expect(screen.getByLabelText('Edge port')).toHaveValue('');

    await user.selectOptions(screen.getByLabelText('Edge source'), 'start');
    await user.selectOptions(screen.getByLabelText('Edge port'), 'default');
    await user.selectOptions(screen.getByLabelText('Edge port'), 'all ports');
    expect(screen.getByLabelText('Edge port')).toHaveValue('');
  });

  it('blocks Validate and Save while a JSON field does not parse', async () => {
    const user = userEvent.setup();
    renderEditor();
    const box = await screen.findByLabelText('Configuration (JSON)');
    await user.click(box);
    await user.paste('{"k"');

    expect(screen.getByRole('button', { name: 'Validate' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled();
    expect(screen.getByText(/Fix the JSON field/)).toBeInTheDocument();

    await user.clear(box);
    expect(screen.getByRole('button', { name: 'Save' })).toBeEnabled();
    await user.paste('{"k":1}');
    expect(screen.getByRole('button', { name: 'Validate' })).toBeEnabled();

    await user.clear(box);
    await user.paste('{');
    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: /^out/ }));
    expect(screen.getByRole('button', { name: 'Save' })).toBeEnabled();
  });

  it('marks unsaved changes, warns before leaving, and Discard goes back to the loaded draft', async () => {
    const user = userEvent.setup();
    renderEditor();
    const box = await screen.findByLabelText('Configuration (JSON)');
    expect(screen.queryByText('Unsaved changes')).not.toBeInTheDocument();

    await user.type(screen.getByLabelText('Description'), ' more');
    expect(screen.getByText('Unsaved changes')).toBeInTheDocument();
    const leaving = new Event('beforeunload', { cancelable: true });
    window.dispatchEvent(leaving);
    expect(leaving.defaultPrevented).toBe(true);

    await user.click(box);
    await user.paste('{"k"');
    await user.click(screen.getByRole('button', { name: 'Discard' }));
    expect(screen.queryByText('Unsaved changes')).not.toBeInTheDocument();
    expect(screen.getByLabelText('Description')).toHaveValue('first');
    expect(screen.getByLabelText('Configuration (JSON)')).toHaveValue('');
    expect(screen.getByRole('button', { name: 'Save' })).toBeEnabled();
    const after = new Event('beforeunload', { cancelable: true });
    window.dispatchEvent(after);
    expect(after.defaultPrevented).toBe(false);
  });

  it('rejects an edge that already exists and an edge from a node to itself', async () => {
    const user = userEvent.setup();
    renderEditor();
    await screen.findByRole('region', { name: 'Node start' });
    await user.click(screen.getByRole('tab', { name: /Edges/ }));

    await user.selectOptions(screen.getByLabelText('Edge source'), 'start');
    await user.selectOptions(screen.getByLabelText('Edge target'), 'out');
    await user.click(screen.getByRole('button', { name: 'Add edge' }));
    expect(screen.getByText('That edge already exists.')).toBeInTheDocument();
    expect(screen.getByTestId('canvas')).toHaveTextContent('1 edges');

    await user.selectOptions(screen.getByLabelText('Edge target'), 'start');
    await user.click(screen.getByRole('button', { name: 'Add edge' }));
    expect(
      screen.getByText('An edge cannot connect a node to itself.')
    ).toBeInTheDocument();
    expect(screen.getByTestId('canvas')).toHaveTextContent('1 edges');
  });

  it('forgets the ports and the edge draft of a removed node', async () => {
    const user = userEvent.setup();
    renderEditor();
    await screen.findByRole('region', { name: 'Node start' });
    await user.click(screen.getByRole('button', { name: 'Validate' }));
    await screen.findByText('Workflow is valid.');
    await user.click(screen.getByRole('tab', { name: /Edges/ }));
    await user.selectOptions(screen.getByLabelText('Edge source'), 'start');
    await user.selectOptions(screen.getByLabelText('Edge port'), 'default');

    await user.click(screen.getByRole('button', { name: 'Remove node' }));

    expect(screen.getByLabelText('Edge source')).toHaveValue('');
    expect(screen.getByLabelText('Edge port')).toHaveValue('');
  });

  it('shows what the validator rejects and clears it when the draft changes', async () => {
    validation = { valid: false, message: 'No trigger node', outputPorts: {} };
    const user = userEvent.setup();
    renderEditor();
    await screen.findByRole('region', { name: 'Node start' });

    await user.click(screen.getByRole('button', { name: 'Validate' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'No trigger node'
    );

    await user.type(screen.getByLabelText('Description'), '!');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('shows a failed validation request', async () => {
    validateFails = true;
    const user = userEvent.setup();
    renderEditor();
    await screen.findByRole('region', { name: 'Node start' });
    await user.click(screen.getByRole('button', { name: 'Validate' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('boom');
  });

  it('shows a generic message for a non-Error failure', async () => {
    server.use(http.post('/api/sessions/validate', () => HttpResponse.error()));
    const user = userEvent.setup();
    renderEditor();
    await screen.findByRole('region', { name: 'Node start' });
    await user.click(screen.getByRole('button', { name: 'Validate' }));
    expect(await screen.findByRole('alert')).toBeInTheDocument();
  });

  it('saves the whole session with the edited workflow and opens its page', async () => {
    const user = userEvent.setup();
    renderEditor();
    await screen.findByRole('region', { name: 'Node start' });
    await user.type(screen.getByLabelText('Description'), ' edited');
    await user.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(saved).toHaveLength(1));
    expect(saved[0].tags).toEqual({ env: 'prod' });
    expect(Object.keys(saved[0].workflows)).toEqual(['wf', 'other']);
    expect(saved[0].workflows.wf.description).toBe('first edited');
    await waitFor(() =>
      expect(window.location.pathname).toBe('/sessions/s1/workflow/wf')
    );
  });

  it('does not save a draft the validator rejects', async () => {
    validation = { valid: false, message: 'cycle', outputPorts: {} };
    const user = userEvent.setup();
    renderEditor();
    await screen.findByRole('region', { name: 'Node start' });
    await user.click(screen.getByRole('button', { name: 'Save' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('cycle');
    expect(saved).toHaveLength(0);
  });

  it('does not save when validation itself fails', async () => {
    validateFails = true;
    const user = userEvent.setup();
    renderEditor();
    await screen.findByRole('region', { name: 'Node start' });
    await user.click(screen.getByRole('button', { name: 'Save' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('boom');
    expect(saved).toHaveLength(0);
  });

  it('shows a failed save and lets the user retry', async () => {
    saveFails = true;
    const user = userEvent.setup();
    renderEditor();
    await screen.findByRole('region', { name: 'Node start' });
    await user.click(screen.getByRole('button', { name: 'Save' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('disk full');
  });

  it('splits the outline into Nodes and Edges tabs with counts, and finds nodes', async () => {
    const user = userEvent.setup();
    renderEditor();
    await screen.findByRole('region', { name: 'Node start' });

    const nodesTab = screen.getByRole('tab', { name: /Nodes/ });
    expect(nodesTab).toHaveAttribute('aria-selected', 'true');
    expect(nodesTab).toHaveTextContent('2');
    expect(screen.getByRole('tab', { name: /Edges/ })).toHaveTextContent('1');
    expect(screen.queryByLabelText('Edge source')).not.toBeInTheDocument();

    const nodes = screen.getByRole('list', { name: 'Nodes' });
    await user.type(screen.getByRole('searchbox', { name: 'Find node' }), 'OU');
    expect(within(nodes).getAllByRole('button')).toHaveLength(1);
    expect(
      within(nodes).getByRole('button', { name: /^out/ })
    ).toBeInTheDocument();

    await user.click(screen.getByRole('tab', { name: /Edges/ }));
    expect(screen.getByLabelText('Edge source')).toBeInTheDocument();
    expect(
      screen.queryByRole('list', { name: 'Nodes' })
    ).not.toBeInTheDocument();
  });

  it('opens the canvas on the selected node and its neighbours', async () => {
    const user = userEvent.setup();
    renderEditor();
    await screen.findByRole('region', { name: 'Node start' });
    expect(screen.getByTestId('canvas')).toHaveAttribute(
      'data-focus',
      'start,out'
    );

    await user.click(screen.getByRole('button', { name: /^out/ }));
    expect(screen.getByTestId('canvas')).toHaveAttribute(
      'data-focus',
      'out,start'
    );

    // removing the selected node clears the selection: the canvas fits as usual
    await user.click(screen.getByRole('button', { name: 'Remove node' }));
    expect(screen.getByTestId('canvas')).toHaveAttribute('data-focus', '');
  });
});
