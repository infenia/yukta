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
import { delay, http, HttpResponse } from 'msw';
import { renderWithProviders } from '@/test/utils/testUtils';
import type { SessionDraft, ValidationResult } from '../../types';
import { WorkflowEditorPage } from '../WorkflowEditorPage';

// The canvas is replaced by one line per node that says what its badge would show.
vi.mock('@/features/workflow/components/WorkflowCanvas', () => ({
  WorkflowCanvas: ({
    graph,
  }: {
    graph: {
      nodes: {
        nodeId: string;
        issues?: { errors: number; warnings: number };
      }[];
    };
  }) => (
    <ul aria-label="Canvas">
      {graph.nodes.map((n) => (
        <li key={n.nodeId}>
          {n.nodeId}:{' '}
          {n.issues ? `${n.issues.errors}e ${n.issues.warnings}w` : 'ok'}
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
      edges: [],
    },
  },
};

let validation: ValidationResult = {
  valid: true,
  message: null,
  outputPorts: {},
};
let validateFails = false;
let firstCheckDelayMs = 0;
let calls = 0;
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
  http.post('/api/sessions/validate', async () => {
    calls += 1;
    if (calls === 1 && firstCheckDelayMs > 0) await delay(firstCheckDelayMs);
    return validateFails
      ? HttpResponse.json({ message: 'boom' }, { status: 500 })
      : ok(validation);
  })
);
beforeAll(() => server.listen());
afterEach(() => {
  sessionStorage.clear();
  server.resetHandlers();
  validation = { valid: true, message: null, outputPorts: {} };
  validateFails = false;
  firstCheckDelayMs = 0;
  calls = 0;
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

const rest = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));
const tab = (name: string) => screen.getByRole('tab', { name });

describe('WorkflowEditorPage live validation', () => {
  it('lists every problem with a way to its node, badges the nodes and opens the right tab', async () => {
    validation = {
      valid: false,
      message: "Node 'out': bad",
      outputPorts: {},
      details: [
        { nodeId: 'out', path: null, code: 'A', message: "Node 'out': bad" },
        {
          nodeId: 'start',
          path: 'engine.retry.maxBackoffMs',
          code: 'B',
          message: "Node 'start': backoff",
        },
        { nodeId: null, path: null, code: 'C', message: 'No terminal' },
        {
          nodeId: null,
          path: 'workflows.wf.assignments[0]',
          code: 'D',
          message: 'assignments[0].supervisor: none',
        },
      ],
      warnings: [{ nodeId: 'start', field: 'default', message: 'unread' }],
    };
    const user = userEvent.setup();
    renderEditor();

    // no Validate click: the draft is checked once it has rested
    const problems = within(
      await screen.findByRole('list', { name: 'Problems' }, { timeout: 3000 })
    );
    expect(problems.getAllByRole('listitem')).toHaveLength(3);
    expect(screen.getByRole('alert')).toHaveTextContent('3 problems');
    // the supervisors' problem is shown with the supervisors
    expect(problems.queryByText(/assignments\[0\]/)).toBeNull();
    const canvas = within(screen.getByRole('list', { name: 'Canvas' }));
    expect(canvas.getByText('out: 1e 0w')).toBeInTheDocument();
    expect(canvas.getByText('start: 1e 1w')).toBeInTheDocument();
    // a problem about the workflow has no node to go to
    expect(problems.getAllByRole('button')).toHaveLength(2);

    await user.click(problems.getByRole('button', { name: 'Go to start' }));
    expect(tab('Policy')).toHaveAttribute('aria-selected', 'true');

    await user.click(problems.getByRole('button', { name: 'Go to out' }));
    expect(
      await screen.findByRole('region', { name: 'Node out' })
    ).toBeInTheDocument();
    expect(tab('Config')).toHaveAttribute('aria-selected', 'true');

    // the tab a problem chose is not kept for the next time the node is selected
    await user.click(problems.getByRole('button', { name: 'Go to start' }));
    await user.click(screen.getByRole('button', { name: /^out/ }));
    await user.click(screen.getByRole('button', { name: /^start/ }));
    expect(tab('Config')).toHaveAttribute('aria-selected', 'true');
  });

  it('says "1 problem" and goes nowhere for one about the workflow', async () => {
    validation = {
      valid: false,
      message: 'No terminal',
      outputPorts: {},
      details: [
        { nodeId: null, path: null, code: 'C', message: 'No terminal' },
      ],
    };
    renderEditor();
    expect(
      await screen.findByRole('alert', {}, { timeout: 3000 })
    ).toHaveTextContent('1 problem');
    expect(screen.getByRole('list', { name: 'Canvas' })).toHaveTextContent(
      'start: ok'
    );
    expect(screen.queryByRole('button', { name: /^Go to/ })).toBeNull();
  });

  it('does not check an empty draft', async () => {
    renderEditor('brand-new');
    await screen.findByRole('heading', { name: 'Edit brand-new' });
    await rest(900);
    expect(calls).toBe(0);
  });

  it('checks once for the draft, and not again while a JSON field does not parse', async () => {
    const user = userEvent.setup();
    renderEditor();
    expect(
      await screen.findByText('Workflow is valid.', {}, { timeout: 3000 })
    ).toBeInTheDocument();
    expect(calls).toBe(1);

    const box = await screen.findByLabelText('Configuration (JSON)');
    await user.click(box);
    await user.paste('{"k"');
    await rest(900);
    expect(calls).toBe(1);
    expect(screen.getByRole('alert')).toHaveTextContent('does not parse');
    expect(screen.queryByRole('list', { name: 'Problems' })).toBeNull();
  });

  it('drops a check that finishes after the draft changed', async () => {
    firstCheckDelayMs = 300;
    const user = userEvent.setup();
    renderEditor();
    // the first check is sent and still in flight when the draft changes
    await waitFor(() => expect(calls).toBe(1), { timeout: 3000 });
    await user.click(await screen.findByLabelText('Configuration (JSON)'));
    await user.paste('{"k":1}');
    // the late answer is ignored; the check for the changed draft is shown
    expect(
      await screen.findByText('Workflow is valid.', {}, { timeout: 3000 })
    ).toBeInTheDocument();
    await waitFor(() => expect(calls).toBe(2), { timeout: 3000 });
  });

  it('stays silent when the check itself fails', async () => {
    validateFails = true;
    renderEditor();
    await screen.findByRole('region', { name: 'Node start' });
    await rest(900);
    expect(calls).toBeGreaterThan(0);
    expect(screen.queryByRole('alert')).toBeNull();
  });
});
