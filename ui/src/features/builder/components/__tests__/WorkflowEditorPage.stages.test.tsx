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
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { renderWithProviders } from '@/test/utils/testUtils';
import type { SessionDraft } from '../../types';
import { WorkflowEditorPage } from '../WorkflowEditorPage';

// The canvas is replaced by its stage view: the open stages and a toggle per stage.
vi.mock('@/features/workflow/components/WorkflowCanvas', () => ({
  WorkflowCanvas: ({
    stageView,
  }: {
    stageView?: {
      open: ReadonlySet<string>;
      onToggle: (stage: string) => void;
    };
  }) => (
    <div
      data-testid="canvas"
      data-open={stageView ? [...stageView.open].join(',') : 'ungrouped'}
    >
      {['lint', 'test'].map((stage) => (
        <button
          key={stage}
          type="button"
          onClick={() => stageView?.onToggle(stage)}
        >
          toggle {stage}
        </button>
      ))}
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
  tags: {},
  projectPath: '/p',
  workflows: {
    wf: {
      workflowId: 'wf',
      description: 'staged',
      nodes: [
        { nodeId: 'unit', type: 'X', config: {}, group: 'test' },
        { nodeId: 'lint', type: 'X', config: {} },
        { nodeId: 'lint.fix', type: 'X', config: {} },
        { nodeId: 'e2e', type: 'X', config: {}, group: 'test' },
        { nodeId: 'alone', type: 'X', config: {} },
      ],
      edges: [],
    },
  },
});

const saved: SessionDraft[] = [];
const server = setupServer(
  http.get('/api/sessions/:sid/config', () => ok(session())),
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
    ok({ valid: true, message: null, outputPorts: {} })
  ),
  http.post('/api/sessions', async ({ request }) => {
    saved.push((await request.json()) as SessionDraft);
    return ok(null);
  })
);
beforeAll(() => server.listen());
afterEach(() => {
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
    </Routes>
  );
}

const nodeButtons = () =>
  screen
    .getAllByRole('button')
    .filter((b) => b.closest('ul[aria-label="Nodes"]'))
    .map((b) => b.textContent?.replace(/X$/, ''));

describe('WorkflowEditorPage stages', () => {
  it('lists a stage’s nodes together under its name, lone nodes without one', async () => {
    renderEditor();
    await screen.findByRole('region', { name: 'Node unit' });
    expect(nodeButtons()).toEqual(['unit', 'e2e', 'lint', 'lint.fix', 'alone']);
    expect(screen.getByTestId('outline-stage-test')).toHaveTextContent('test2');
    expect(screen.getByTestId('outline-stage-lint')).toHaveTextContent('lint2');
    expect(screen.queryByTestId('outline-stage-alone')).not.toBeInTheDocument();
  });

  it('keeps the selected node’s stage open; closing it deselects, and grouping can be off', async () => {
    const user = userEvent.setup();
    renderEditor();
    await screen.findByRole('region', { name: 'Node unit' });
    const canvas = screen.getByTestId('canvas');
    expect(canvas).toHaveAttribute('data-open', 'test');

    await user.click(screen.getByRole('button', { name: 'toggle lint' }));
    expect(canvas).toHaveAttribute('data-open', 'test,lint');
    await user.click(screen.getByRole('button', { name: 'toggle lint' }));
    expect(canvas).toHaveAttribute('data-open', 'test');
    expect(
      screen.getByRole('region', { name: 'Node unit' })
    ).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'toggle lint' }));
    await user.click(screen.getByRole('button', { name: 'toggle test' }));
    expect(screen.getByTestId('canvas')).toHaveAttribute('data-open', 'lint');
    expect(
      screen.getByText('Select a node to configure it.')
    ).toBeInTheDocument();

    await user.click(screen.getByRole('radio', { name: 'All nodes' }));
    expect(screen.getByTestId('canvas')).toHaveAttribute(
      'data-open',
      'ungrouped'
    );
  });

  it('sets and clears a node’s stage, and saves it', async () => {
    const user = userEvent.setup();
    renderEditor();
    await user.click(await screen.findByRole('button', { name: /^alone/ }));
    const stage = screen.getByLabelText('Stage');
    expect(stage).toHaveAttribute('placeholder', 'alone');
    await user.type(stage, 'lint');
    expect(screen.getByTestId('outline-stage-lint')).toHaveTextContent('lint3');

    await user.click(screen.getByRole('button', { name: /^unit/ }));
    await user.clear(screen.getByLabelText('Stage'));
    await user.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(saved).toHaveLength(1));
    const nodes = saved[0].workflows.wf.nodes;
    expect(nodes.find((n) => n.nodeId === 'alone')?.group).toBe('lint');
    expect(nodes.find((n) => n.nodeId === 'unit')).not.toHaveProperty('group');
  });
});
