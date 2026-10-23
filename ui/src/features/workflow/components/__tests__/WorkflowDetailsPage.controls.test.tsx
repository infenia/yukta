// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
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
} from '@/test/factories/workflowFactory';
import { installMockEventSource } from '@/test/mocks/MockEventSource';

const ok = (data: unknown) =>
  HttpResponse.json({ timestamp: 't', status: 200, message: 'ok', data, path: '/' });

const calls: string[] = [];
let nodeFails = false;

const server = setupServer(
  http.get('/api/sessions/:sid/workflows/:wid/graph', () => ok(createMockGraph())),
  http.get('/api/sessions/:sid/workflows/:wid/executions', () =>
    ok([createMockExecution(), createMockExecution({ executionId: 'restarted' })])
  ),
  http.get('/api/executions/:eid', ({ params }) =>
    ok(createMockProgress({ executionId: String(params.eid) }))
  ),
  http.post('/api/executions/:id/restart/:node', ({ params }) => {
    calls.push(`restart-from ${params.node}`);
    return ok({ executionId: 'restarted' });
  }),
  http.post('/api/executions/:id/nodes/:node/:action', ({ params }) => {
    calls.push(`${params.action} ${params.node}`);
    return nodeFails
      ? HttpResponse.json({ message: 'Core rejected it' }, { status: 409 })
      : ok({ executionId: 'exec-091-qp-55' });
  })
);

beforeAll(() => server.listen());
beforeEach(() => installMockEventSource());
afterEach(() => {
  calls.length = 0;
  nodeFails = false;
});
afterAll(() => server.close());

function Where() {
  const location = useLocation();
  return <output data-testid="where">{location.pathname + location.search}</output>;
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
    expect(edit).toHaveAttribute('href', '/sessions/S%201/workflow/wf-982-xk-11/edit');
    expect(screen.getByTestId('run-controls')).toBeInTheDocument();
  });

  it('sends a node action for the selected execution', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole('button', { name: 'Actions for vectorize-batch' }));
    await user.click(await screen.findByRole('menuitem', { name: 'Pause node' }));
    await waitFor(() => expect(calls).toEqual(['pause vectorize-batch']));
  });

  it('restarts from a node and selects the new execution', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole('button', { name: 'Actions for data-ingress' }));
    await user.click(await screen.findByRole('menuitem', { name: 'Restart from here' }));
    await waitFor(() => expect(screen.getByTestId('where')).toHaveTextContent('?exec=restarted'));
    expect(calls).toEqual(['restart-from data-ingress']);
  });

  it('shows why a node action failed', async () => {
    nodeFails = true;
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole('button', { name: 'Actions for vectorize-batch' }));
    await user.click(await screen.findByRole('menuitem', { name: 'Pause node' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Core rejected it');
  });
});
