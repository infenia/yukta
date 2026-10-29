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
import { BreadcrumbOverridesProvider } from '@/lib/breadcrumbOverrides';
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
  http.get('/api/executions/:eid', ({ params }) =>
    ok(
      createMockProgress({
        executionId: String(params.eid),
        ...(skipped && {
          tasks: [
            createMockTask({
              nodeId: 'anomaly-detection',
              status: 'NODE_SKIPPED',
            }),
          ],
        }),
      })
    )
  ),
  http.post('/api/executions/:id/restart/:node', ({ params }) => {
    calls.push(`restart-from ${params.node}`);
    return ok({ executionId: 'restarted' });
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
      <BreadcrumbOverridesProvider>
        <MemoryRouter initialEntries={['/sessions/S 1/workflow/wf-982-xk-11']}>
          <div id="header-actions" />
          <div id="breadcrumb-actions" />
          <Where />
          <Routes>
            <Route
              path="/sessions/:sessionId/workflow/:workflowId"
              element={<WorkflowDetailsPage />}
            />
          </Routes>
        </MemoryRouter>
      </BreadcrumbOverridesProvider>
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
