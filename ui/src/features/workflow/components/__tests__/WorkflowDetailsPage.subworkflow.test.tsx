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
import { fireEvent, render, screen, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import { QueryClientProvider } from '@tanstack/react-query';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { WorkflowDetailsPage } from '../WorkflowDetailsPage';
import { createTestQueryClient } from '@/test/utils/testUtils';
import {
  createMockExecution,
  createMockGraph,
  createMockGraphNode,
  createMockProgress,
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

const SESSION = 'SESSION_A92_XP_2024';
const graphs = {
  parent: createMockGraph({
    workflowId: 'parent',
    nodes: [
      createMockGraphNode({
        nodeId: 'run-child',
        type: 'sub-workflow',
        category: 'PROCESSOR',
        subWorkflowId: 'child',
      }),
    ],
  }),
  child: createMockGraph({
    workflowId: 'child',
    nodes: [createMockGraphNode({ nodeId: 'child-step' })],
  }),
};
const runs = {
  parent: createMockExecution({
    executionId: 'exec-parent',
    workflowId: 'parent',
  }),
  child: createMockExecution({
    executionId: 'exec-child',
    workflowId: 'child',
    status: 'SUCCESS',
    endTime: '2026-07-26T14:30:00',
  }),
};

const server = setupServer(
  http.get('/api/sessions/:s/workflows/:w/graph', ({ params }) =>
    ok(graphs[params.w as 'parent' | 'child'])
  ),
  http.get('/api/sessions/:s/workflows/:w/executions', ({ params }) =>
    ok([runs[params.w as 'parent' | 'child']])
  ),
  http.get('/api/sessions/:s/approvals', () => ok([])),
  http.get('/api/executions/:id/tree', () =>
    ok([{ ...runs.child, parentExecutionId: 'exec-parent' }])
  ),
  http.get('/api/executions/:id', ({ params }) => {
    const child = params.id === 'exec-child';
    return ok(
      createMockProgress({
        executionId: params.id as string,
        workflowId: child ? 'child' : 'parent',
        status: child ? 'COMPLETED' : 'RUNNING',
        parentExecutionId: child ? 'exec-parent' : null,
        tasks: [],
      })
    );
  })
);

beforeAll(() => server.listen());
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function Loc() {
  const l = useLocation();
  return <output data-testid="loc">{l.pathname + l.search}</output>;
}

describe('opening a sub-workflow from a run', () => {
  beforeEach(() => installMockEventSource());

  it('opens the child workflow on the child run, with no error', async () => {
    render(
      <QueryClientProvider client={createTestQueryClient()}>
        <MemoryRouter initialEntries={[`/sessions/${SESSION}/workflow/parent`]}>
          <Routes>
            <Route
              path="/sessions/:sessionId/workflow/:workflowId"
              element={<WorkflowDetailsPage />}
            />
          </Routes>
          <Loc />
        </MemoryRouter>
      </QueryClientProvider>
    );

    // a bare click: user-event's mousedown has no window for the canvas's d3-zoom in jsdom
    fireEvent.click(
      await screen.findByRole('link', { name: 'Open sub-workflow child' })
    );

    const title = await screen.findByRole('heading', {
      level: 1,
      name: 'child',
    });
    expect(title).toBeInTheDocument();
    expect(screen.getByTestId('loc')).toHaveTextContent(
      `/workflow/child?exec=exec-child`
    );
    expect(screen.queryByText('Failed to load workflow')).toBeNull();
    expect(
      within(screen.getByTestId('workflow-details-page')).getByText(
        /child-step/
      )
    ).toBeInTheDocument();
  });
});
