// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import {
  describe,
  it,
  expect,
  beforeAll,
  afterAll,
  afterEach,
  beforeEach,
} from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import { QueryClientProvider } from '@tanstack/react-query';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { WorkflowDetailsPage } from '../WorkflowDetailsPage';
import { createTestQueryClient } from '@/test/utils/testUtils';
import {
  createMockExecution,
  createMockGraph,
  createMockProgress,
  createMockTask,
} from '@/test/factories/workflowFactory';
import { installMockEventSource } from '@/test/mocks/MockEventSource';

const ok = (data: unknown) =>
  HttpResponse.json({
    timestamp: 't',
    status: 200,
    message: 'ok',
    data,
    path: '/',
  });

const calls: string[] = [];
let nodeFails = false;
/** The breakpoint the mock server holds on vectorize-batch, as the status snapshot reports it. */
let breakpoint: { field: string; equals: string } | null = null;
let skipped = false;

const server = setupServer(
  http.get('/api/sessions/:sid/workflows/:wid/graph', () =>
    ok(createMockGraph())
  ),
  http.get('/api/sessions/:sid/workflows/:wid/executions', () =>
    ok([
      createMockExecution(),
      createMockExecution({ executionId: 'restarted' }),
    ])
  ),
  http.get('/api/executions/:eid', ({ params }) => {
    const progress = createMockProgress({
      executionId: String(params.eid),
      ...(skipped && {
        tasks: [
          createMockTask({
            nodeId: 'anomaly-detection',
            status: 'NODE_SKIPPED',
          }),
        ],
      }),
    });
    return ok({
      ...progress,
      tasks: progress.tasks.map((t) =>
        t.nodeId === 'vectorize-batch' ? { ...t, breakpoint } : t
      ),
    });
  }),
  http.post('/api/executions/:id/restart/:node', ({ params }) => {
    calls.push(`restart-from ${params.node}`);
    return ok({ executionId: 'restarted' });
  }),
  http.post(
    '/api/executions/:id/nodes/:node/breakpoint',
    ({ params, request }) => {
      const url = new URL(request.url);
      calls.push(`breakpoint ${params.node}${url.search}`);
      if (nodeFails)
        return HttpResponse.json(
          { message: 'Core rejected it' },
          { status: 409 }
        );
      breakpoint = {
        field: url.searchParams.get('field') ?? '',
        equals: url.searchParams.get('equals') ?? '',
      };
      return ok({ executionId: 'exec-091-qp-55' });
    }
  ),
  http.delete('/api/executions/:id/nodes/:node/breakpoint', ({ params }) => {
    calls.push(`clear-breakpoint ${params.node}`);
    if (nodeFails)
      return HttpResponse.json(
        { message: 'Core rejected it' },
        { status: 409 }
      );
    breakpoint = null;
    return ok({ executionId: 'exec-091-qp-55' });
  }),
  http.post(
    '/api/executions/:id/nodes/:node/:action',
    ({ params, request }) => {
      const url = new URL(request.url);
      calls.push(`${params.action} ${params.node}${url.search}`);
      return nodeFails
        ? HttpResponse.json({ message: 'Core rejected it' }, { status: 409 })
        : ok({ executionId: 'exec-091-qp-55' });
    }
  )
);

beforeAll(() => server.listen());
beforeEach(() => installMockEventSource());
afterEach(() => {
  calls.length = 0;
  nodeFails = false;
  breakpoint = null;
  skipped = false;
});
afterAll(() => server.close());

function Where() {
  const location = useLocation();
  return (
    <output data-testid="where">{location.pathname + location.search}</output>
  );
}

function renderPage() {
  return render(
    <QueryClientProvider client={createTestQueryClient()}>
      <MemoryRouter initialEntries={['/sessions/S 1/workflow/wf-982-xk-11']}>
        <Where />
        <Routes>
          <Route
            path="/sessions/:sessionId/workflow/:workflowId"
            element={<WorkflowDetailsPage />}
          />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  );
}

describe('WorkflowDetailsPage controls', () => {
  it('links to the editor and shows the run controls', async () => {
    renderPage();
    const edit = await screen.findByRole('link', { name: /edit/i });
    expect(edit).toHaveAttribute(
      'href',
      '/sessions/S%201/workflow/wf-982-xk-11/edit'
    );
    expect(screen.getByTestId('run-controls')).toBeInTheDocument();
  });

  it('opens the typed confirmation to delete the workflow', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(
      await screen.findByRole('button', { name: 'Workflow actions' })
    );
    await user.click(
      await screen.findByRole('menuitem', { name: 'Delete workflow…' })
    );
    expect(
      screen.getByRole('dialog', { name: 'Delete workflow wf-982-xk-11' })
    ).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('sends a node action for the selected execution', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(
      await screen.findByRole('button', { name: 'Actions for vectorize-batch' })
    );
    await user.click(
      await screen.findByRole('menuitem', { name: 'Pause node' })
    );
    await waitFor(() => expect(calls).toEqual(['pause vectorize-batch']));
  });

  it('restarts from a node and selects the new execution', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(
      await screen.findByRole('button', { name: 'Actions for data-ingress' })
    );
    await user.click(
      await screen.findByRole('menuitem', { name: 'Restart from here' })
    );
    await waitFor(() =>
      expect(screen.getByTestId('where')).toHaveTextContent('?exec=restarted')
    );
    expect(calls).toEqual(['restart-from data-ingress']);
  });

  it('stops a node with the chosen options after asking', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(
      await screen.findByRole('button', { name: 'Actions for vectorize-batch' })
    );
    await user.click(
      await screen.findByRole('menuitem', { name: 'Stop node…' })
    );
    const dialog = screen.getByRole('dialog', {
      name: 'Stop vectorize-batch?',
    });
    expect(dialog).toHaveTextContent('recorded in the control audit');
    await user.click(screen.getByLabelText('Stop immediately (do not drain)'));
    await user.type(screen.getByLabelText('Reason (optional)'), ' too slow ');
    await user.click(within(dialog).getByRole('button', { name: 'Stop' }));
    await waitFor(() =>
      expect(calls).toEqual([
        'stop vectorize-batch?immediate=true&reason=too+slow',
      ])
    );
    await waitFor(() =>
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    );
  });

  it('stops a node without options and can cancel the dialog', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(
      await screen.findByRole('button', { name: 'Actions for vectorize-batch' })
    );
    await user.click(
      await screen.findByRole('menuitem', { name: 'Stop node…' })
    );
    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(calls).toEqual([]);

    await user.click(
      screen.getByRole('button', { name: 'Actions for vectorize-batch' })
    );
    await user.click(
      await screen.findByRole('menuitem', { name: 'Stop node…' })
    );
    await user.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: 'Stop' })
    );
    await waitFor(() => expect(calls).toEqual(['stop vectorize-batch']));
  });

  it('unskips a skipped node', async () => {
    skipped = true;
    const user = userEvent.setup();
    renderPage();
    await user.click(
      await screen.findByRole('button', {
        name: 'Actions for anomaly-detection',
      })
    );
    await user.click(
      await screen.findByRole('menuitem', { name: 'Unskip node' })
    );
    await waitFor(() =>
      expect(calls).toEqual(['skip anomaly-detection?skip=false'])
    );
  });

  it('sets a breakpoint, shows the marker from the snapshot, and clears it', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(
      await screen.findByRole('button', { name: 'Actions for vectorize-batch' })
    );
    await user.click(
      await screen.findByRole('menuitem', { name: 'Set breakpoint…' })
    );
    await user.type(screen.getByLabelText('Field'), 'payload.status');
    await user.type(screen.getByLabelText('Equals'), 'error');
    await user.click(screen.getByRole('button', { name: 'Set' }));

    expect(
      await screen.findByRole('img', {
        name: 'Breakpoint: payload.status = error',
      })
    ).toBeInTheDocument();
    expect(calls).toEqual([
      'breakpoint vectorize-batch?field=payload.status&equals=error',
    ]);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();

    await user.click(
      screen.getByRole('button', { name: 'Actions for vectorize-batch' })
    );
    await user.click(
      await screen.findByRole('menuitem', { name: 'Clear breakpoint' })
    );
    await waitFor(() =>
      expect(
        screen.queryByRole('img', { name: /Breakpoint/ })
      ).not.toBeInTheDocument()
    );
    expect(calls).toContain('clear-breakpoint vectorize-batch');
  });

  it('keeps the dialog and shows the reason when the breakpoint is refused', async () => {
    nodeFails = true;
    const user = userEvent.setup();
    renderPage();
    await user.click(
      await screen.findByRole('button', { name: 'Actions for vectorize-batch' })
    );
    await user.click(
      await screen.findByRole('menuitem', { name: 'Set breakpoint…' })
    );
    await user.type(screen.getByLabelText('Field'), 'payload.status');
    await user.type(screen.getByLabelText('Equals'), 'error');
    await user.click(screen.getByRole('button', { name: 'Set' }));

    expect(
      await within(screen.getByRole('dialog')).findByRole('alert')
    ).toHaveTextContent('Core rejected it');
    expect(
      screen.queryByRole('img', { name: /Breakpoint/ })
    ).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('keeps the marker and shows the reason when clearing is refused', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(
      await screen.findByRole('button', { name: 'Actions for vectorize-batch' })
    );
    await user.click(
      await screen.findByRole('menuitem', { name: 'Set breakpoint…' })
    );
    await user.type(screen.getByLabelText('Field'), 'a');
    await user.type(screen.getByLabelText('Equals'), 'b');
    await user.click(screen.getByRole('button', { name: 'Set' }));
    await screen.findByRole('img', { name: /Breakpoint/ });

    nodeFails = true;
    await user.click(
      screen.getByRole('button', { name: 'Actions for vectorize-batch' })
    );
    await user.click(
      await screen.findByRole('menuitem', { name: 'Clear breakpoint' })
    );
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Core rejected it'
    );
    expect(screen.getByRole('img', { name: /Breakpoint/ })).toBeInTheDocument();
  });

  it('shows why a node action failed', async () => {
    nodeFails = true;
    const user = userEvent.setup();
    renderPage();
    await user.click(
      await screen.findByRole('button', { name: 'Actions for vectorize-batch' })
    );
    await user.click(
      await screen.findByRole('menuitem', { name: 'Pause node' })
    );
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Core rejected it'
    );
  });
});
