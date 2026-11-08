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

vi.mock('@/features/workflow/components/WorkflowCanvas', () => ({
  WorkflowCanvas: () => <div data-testid="canvas" />,
}));

const ok = (data: unknown, status = 200) =>
  HttpResponse.json(
    { timestamp: 't', status, message: 'ok', data, path: '/' },
    { status }
  );

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
      description: 'w',
      nodes: [
        { nodeId: 'build.a', type: 'X', config: {} },
        { nodeId: 'end', type: 'X', config: {} },
      ],
      edges: [],
      assignments: [{ supervisor: 'guard' }],
    },
  },
});

let validation: unknown = { valid: true, message: null, outputPorts: {} };
let refuse = false;
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
  http.get('/api/supervisors', () =>
    ok([
      { id: 'guard', type: 't', system: false },
      { id: 'watch', type: 't', system: false },
    ])
  ),
  http.post('/api/sessions/validate', () => ok(validation)),
  http.post('/api/sessions', async ({ request }) => {
    if (refuse) {
      return HttpResponse.json(
        {
          status: 400,
          message: 'Invalid supervisors',
          errors: [
            {
              field: 'workflows.wf.assignments[0].supervisor',
              message: "no supervisor 'guard'",
            },
            { field: 'name', message: 'other' },
          ],
        },
        { status: 400 }
      );
    }
    saved.push((await request.json()) as SessionDraft);
    return ok(null);
  })
);
beforeAll(() => server.listen());
afterEach(() => {
  saved.length = 0;
  refuse = false;
  validation = { valid: true, message: null, outputPorts: {} };
  window.history.pushState({}, '', '/');
});
afterAll(() => server.close());

function renderEditor(url = '/sessions/s1/workflow/wf/edit', state?: unknown) {
  window.history.pushState(state ?? {}, '', url);
  return renderWithProviders(
    <Routes>
      <Route
        path="sessions/:sessionId/workflow/:workflowId/edit"
        element={<WorkflowEditorPage />}
      />
    </Routes>
  );
}

describe('the supervisors of a workflow in the editor', () => {
  it('shows what the workflow declares and saves a change with the version', async () => {
    const user = userEvent.setup();
    renderEditor();
    await user.click(await screen.findByText(/Supervisors \(1\)/));

    await user.selectOptions(screen.getByLabelText('Supervisor 1'), 'watch');
    expect(screen.getByText('Unsaved changes')).toBeInTheDocument();
    await user.click(screen.getByLabelText('Selected nodes'));
    await user.click(await screen.findByLabelText('build.a'));
    await user.click(screen.getByRole('button', { name: 'Save version' }));

    await waitFor(() => expect(saved).toHaveLength(1));
    expect(saved[0].workflows.wf.assignments).toEqual([
      { supervisor: 'watch', nodes: ['build.a'] },
    ]);
  });

  it('shows the problems Validate and a refused save find, in the panel', async () => {
    validation = {
      valid: true,
      message: null,
      outputPorts: {},
      problems: ['assignments[0].supervisor: no supervisor', 'something else'],
    };
    const user = userEvent.setup();
    renderEditor();
    await screen.findByText(/Supervisors \(1\)/);
    await user.click(screen.getByRole('button', { name: 'Validate' }));
    const alerts = await screen.findAllByText(
      'assignments[0].supervisor: no supervisor'
    );
    expect(alerts.length).toBeGreaterThan(0);
    expect(screen.queryByText('something else')).toBeNull();

    refuse = true;
    await user.click(screen.getByRole('button', { name: 'Save version' }));
    expect(
      await screen.findByText(
        "workflows.wf.assignments[0].supervisor: no supervisor 'guard'"
      )
    ).toBeInTheDocument();
  });

  it('starts a new workflow with the supervisors the dialog picked', async () => {
    renderEditor('/sessions/s1/workflow/fresh/edit', {
      usr: { assignments: [{ supervisor: 'watch' }] },
      key: 'k',
      idx: 0,
    });
    const summary = await screen.findByText(/Supervisors \(/);
    expect(
      within(summary.closest('details') as HTMLElement).getByLabelText(
        'Supervisor 1'
      )
    ).toBeInTheDocument();
  });

  it('starts a new workflow with none when the dialog picked none', async () => {
    renderEditor('/sessions/s1/workflow/plain/edit');
    expect(await screen.findByText('Supervisors (0)')).toBeInTheDocument();
  });
});
