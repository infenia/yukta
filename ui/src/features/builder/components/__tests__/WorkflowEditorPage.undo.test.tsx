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

vi.mock('@/features/workflow/components/WorkflowCanvas', () => ({
  WorkflowCanvas: ({ graph }: { graph: { nodes: unknown[] } }) => (
    <div data-testid="canvas">{graph.nodes.length} nodes</div>
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
      description: 'first',
      nodes: [
        { nodeId: 'start', type: 'API_TRIGGER', config: {} },
        { nodeId: 'out', type: 'CONSOLE_TERMINAL', config: {} },
      ],
      edges: [{ source: 'start', target: 'out', sourcePort: null }],
    },
    empty: { workflowId: 'empty', description: '', nodes: [], edges: [] },
  },
};
const server = setupServer(
  http.get('/api/sessions/:sid/config', () => ok(session)),
  http.get('/api/plugins', () =>
    ok([
      { type: 'API_TRIGGER', category: 'TRIGGER', description: 'Starts a run' },
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
  )
);
beforeAll(() => server.listen());
afterEach(() => {
  sessionStorage.clear();
  server.resetHandlers();
  window.history.pushState({}, '', '/');
});
afterAll(() => server.close());

function renderEditor(workflowId = 'wf') {
  window.history.pushState({}, '', `/sessions/s1/workflow/${workflowId}/edit`);
  return renderWithProviders(
    <Routes>
      <Route
        path="sessions/:sessionId/workflow/:workflowId/edit"
        element={<WorkflowEditorPage />}
      />
    </Routes>
  );
}

const undo = () => screen.getByRole('button', { name: 'Undo' });
const redo = () => screen.getByRole('button', { name: 'Redo' });

describe('WorkflowEditorPage undo and redo', () => {
  it('undoes and redoes an edit with the buttons, and the draft stops being unsaved', async () => {
    const user = userEvent.setup();
    renderEditor();
    const description = await screen.findByLabelText('Description');
    expect(undo()).toBeDisabled();
    expect(redo()).toBeDisabled();

    await user.type(description, '!!');
    expect(description).toHaveValue('first!!');
    expect(screen.getByText('Unsaved changes')).toBeInTheDocument();

    // typing a word is one step
    await user.click(undo());
    expect(description).toHaveValue('first');
    expect(screen.queryByText('Unsaved changes')).toBeNull();
    expect(undo()).toBeDisabled();

    await user.click(redo());
    expect(description).toHaveValue('first!!');
    expect(redo()).toBeDisabled();
  });

  it('undoes the removal of a node, and answers to the keyboard but not while typing', async () => {
    const user = userEvent.setup();
    renderEditor();
    await screen.findByRole('region', { name: 'Node start' });
    await user.click(screen.getByRole('button', { name: 'Remove node' }));
    expect(screen.getByTestId('canvas')).toHaveTextContent('1 nodes');

    await user.keyboard('{Control>}z{/Control}');
    expect(screen.getByTestId('canvas')).toHaveTextContent('2 nodes');
    await user.keyboard('{Control>}{Shift>}z{/Shift}{/Control}');
    expect(screen.getByTestId('canvas')).toHaveTextContent('1 nodes');
    await user.keyboard('{Control>}z{/Control}');
    expect(screen.getByTestId('canvas')).toHaveTextContent('2 nodes');
    await user.keyboard('{Control>}y{/Control}');
    expect(screen.getByTestId('canvas')).toHaveTextContent('1 nodes');

    // in a text field the keys belong to the field
    await user.click(screen.getByLabelText('Description'));
    await user.keyboard('{Control>}z{/Control}');
    expect(screen.getByTestId('canvas')).toHaveTextContent('1 nodes');
    // other keys with Ctrl do nothing
    await user.click(document.body);
    await user.keyboard('{Control>}q{/Control}');
    expect(screen.getByTestId('canvas')).toHaveTextContent('1 nodes');
  });

  it('starts over on Discard', async () => {
    const user = userEvent.setup();
    renderEditor();
    await user.type(await screen.findByLabelText('Description'), 'x');
    await user.click(screen.getByRole('button', { name: 'Discard' }));
    expect(screen.getByLabelText('Description')).toHaveValue('first');
    expect(undo()).toBeDisabled();
  });
});

describe('WorkflowEditorPage starter', () => {
  it('asks for a trigger on an empty workflow, and adds the one picked', async () => {
    const user = userEvent.setup();
    renderEditor('empty');
    expect(
      await screen.findByRole('heading', { name: 'Start with a trigger' })
    ).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Pick a trigger' }));
    const list = await screen.findByRole('region', { name: 'Triggers' });
    // only triggers are offered
    expect(screen.queryByRole('region', { name: 'Terminals' })).toBeNull();
    await user.click(within(list).getByRole('button', { name: /Api Trigger/ }));

    expect(screen.getByTestId('canvas')).toHaveTextContent('1 nodes');
    expect(
      screen.queryByRole('heading', { name: 'Start with a trigger' })
    ).toBeNull();
  });

  it('does not show on a workflow that has nodes', async () => {
    renderEditor();
    await screen.findByRole('region', { name: 'Node start' });
    expect(
      screen.queryByRole('heading', { name: 'Start with a trigger' })
    ).toBeNull();
  });
});

describe('WorkflowEditorPage shortcuts', () => {
  it('lists the keys it answers to', async () => {
    const user = userEvent.setup();
    renderEditor();
    await user.click(
      await screen.findByRole('button', { name: 'Keyboard shortcuts' })
    );
    expect(await screen.findByText('Undo', { selector: 'dd' })).toBeVisible();
    expect(screen.getByText('Remove the selected node')).toBeVisible();
  });
});
