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
import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router';
import { QueryClientProvider } from '@tanstack/react-query';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { WorkflowDetailsPage } from '../WorkflowDetailsPage';
import { createTestQueryClient, must } from '@/test/utils/testUtils';
import {
  createMockExecution,
  createMockGraph,
  createMockProgress,
  createStagedGraph,
} from '@/test/factories/workflowFactory';
import {
  installMockEventSource,
  MockEventSource,
} from '@/test/mocks/MockEventSource';

let executionsPayload = () => [
  createMockExecution(),
  createMockExecution({
    executionId: 'exec-old-1',
    status: 'SUCCESS',
    endTime: '2026-07-25T10:00:00',
  }),
];

const server = setupServer(
  http.get('/api/sessions/:sessionId/workflows/:workflowId/graph', () =>
    HttpResponse.json({
      timestamp: 't',
      status: 200,
      message: 'ok',
      data: createMockGraph(),
      path: '/graph',
    })
  ),
  http.get('/api/sessions/:sessionId/workflows/:workflowId/executions', () =>
    HttpResponse.json({
      timestamp: 't',
      status: 200,
      message: 'ok',
      data: executionsPayload(),
      path: '/executions',
    })
  ),
  http.get('/api/executions/:executionId', ({ params }) =>
    HttpResponse.json({
      timestamp: 't',
      status: 200,
      message: 'ok',
      data: createMockProgress(
        params.executionId === 'exec-old-1'
          ? {
              executionId: 'exec-old-1',
              status: 'COMPLETED',
              endTime: '2026-07-25T10:05:00',
            }
          : {}
      ),
      path: '/status',
    })
  )
);

beforeAll(() => server.listen());
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function renderPage(
  initialEntry = '/sessions/SESSION_A92_XP_2024/workflow/wf-982-xk-11'
) {
  return render(
    <QueryClientProvider client={createTestQueryClient()}>
      <MemoryRouter initialEntries={[initialEntry]}>
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

describe('WorkflowDetailsPage', () => {
  beforeEach(() => {
    installMockEventSource();
    executionsPayload = () => [
      createMockExecution(),
      createMockExecution({
        executionId: 'exec-old-1',
        status: 'SUCCESS',
        endTime: '2026-07-25T10:00:00',
      }),
    ];
  });

  it('groups the canvas by stage, and shows every node on request', async () => {
    server.use(
      http.get('/api/sessions/:sessionId/workflows/:workflowId/graph', () =>
        HttpResponse.json({
          timestamp: 't',
          status: 200,
          message: 'ok',
          data: createStagedGraph(),
          path: '/graph',
        })
      )
    );
    const user = userEvent.setup();
    renderPage('/sessions/SESSION_A92_XP_2024/workflow/gate?open=test');
    expect(await screen.findByTestId('stage-lint')).toBeInTheDocument();
    expect(screen.getByTestId('stage-frame-test')).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: 'Group by stage' })).toBeChecked();
    // a stage box is not a node the logs can narrow to
    fireEvent.click(screen.getByTestId('stage-lint'));
    expect(screen.queryByText(/^Node: /)).not.toBeInTheDocument();

    await user.click(screen.getByRole('radio', { name: 'All nodes' }));
    expect(
      await screen.findByTestId('workflow-node-lint.fix')
    ).toBeInTheDocument();
    expect(screen.queryByTestId('stage-lint')).not.toBeInTheDocument();
  });

  it('opens a sub-workflow from its node, and links a sub-workflow run back to its parent', async () => {
    server.use(
      http.get('/api/sessions/:sessionId/workflows/:workflowId/graph', () =>
        HttpResponse.json({ timestamp: 't', status: 200, message: 'ok', data: createStagedGraph(), path: '/graph' })
      ),
      http.get('/api/executions/:executionId/tree', () =>
        HttpResponse.json({ timestamp: 't', status: 200, message: 'ok', path: '/', data: [] })
      ),
      http.get('/api/executions/:executionId', ({ params }) =>
        HttpResponse.json({
          timestamp: 't', status: 200, message: 'ok', path: '/status',
          data:
            params.executionId === 'parent-run'
              ? createMockProgress({ executionId: 'parent-run', workflowId: 'outer' })
              : createMockProgress({ parentExecutionId: 'parent-run' }),
        })
      )
    );
    renderPage('/sessions/SESSION_A92_XP_2024/workflow/gate?stages=all');
    expect(await screen.findByRole('link', { name: 'Open sub-workflow fix' })).toHaveAttribute(
      'href',
      '/sessions/SESSION_A92_XP_2024/workflow/fix'
    );
    expect(await screen.findByRole('link', { name: 'Parent run' })).toHaveAttribute(
      'href',
      '/sessions/SESSION_A92_XP_2024/workflow/outer?exec=parent-run'
    );
  });

  it('narrows the logs to a node clicked on the canvas', async () => {
    const user = userEvent.setup();
    renderPage();
    // fireEvent: user-event's pointer events on the xyflow pane outlive the test
    fireEvent.click(await screen.findByTestId('workflow-node-vectorize-batch'));
    expect(
      await screen.findByText('Node: vectorize-batch')
    ).toBeInTheDocument();
    await user.click(
      screen.getByRole('button', {
        name: "Show every node's lines, not only vectorize-batch's",
      })
    );
    expect(screen.queryByText('Node: vectorize-batch')).not.toBeInTheDocument();
  });

  it('follows the running nodes of a live run until told not to', async () => {
    const user = userEvent.setup();
    renderPage();
    const follow = await screen.findByRole('button', { name: 'Follow running nodes' });
    expect(follow).toHaveAttribute('aria-pressed', 'true');
    await user.click(follow);
    expect(follow).toHaveAttribute('aria-pressed', 'false');
  });

  it('shows a loading state before data arrives', () => {
    renderPage();
    expect(screen.getByText('Loading workflow...')).toBeInTheDocument();
  });

  it('renders the full page for a running workflow', async () => {
    renderPage();

    expect(
      await screen.findByTestId('workflow-details-page')
    ).toBeInTheDocument();
    // the ID is the title; the description sits under it
    expect(
      screen.getByRole('heading', { name: 'wf-982-xk-11' })
    ).toBeInTheDocument();
    expect(screen.getByText('Supply_Chain_Optimizer_V2')).toBeInTheDocument();
    expect(screen.getByTestId('workflow-canvas')).toBeInTheDocument();
    expect(screen.getByTestId('progress-panel')).toBeInTheDocument();
    expect(screen.getByTestId('logs-panel')).toBeInTheDocument();

    await waitFor(() =>
      expect(screen.getByTestId('status-chip')).toHaveTextContent('Running')
    );
    expect(screen.getByTestId('workflow-node-data-ingress')).toHaveTextContent(
      'Succeeded'
    );
  });

  it('keeps the run picker and the progress toggle in its own toolbar', async () => {
    renderPage();
    await screen.findByTestId('workflow-details-page');

    const toolbar = must(
      screen.getByRole('heading', { name: 'wf-982-xk-11' }).parentElement
    );
    expect(toolbar.textContent).toContain('Run History:');
    expect(toolbar.textContent).toContain('exec-091-qp-55');
    expect(
      within(toolbar).getByRole('button', { name: 'Workflow progress' })
    ).toHaveAttribute('aria-pressed', 'true');
  });

  it('opens and closes the versions panel', async () => {
    server.use(
      http.get('/api/sessions/:sessionId/workflows/:workflowId/versions', () =>
        HttpResponse.json({
          timestamp: 't',
          status: 200,
          message: 'ok',
          data: [{ id: '0001-a', savedAt: '2026-10-13T10:00:00Z' }],
          path: '/versions',
        })
      )
    );
    renderPage();
    const menu = await screen.findByRole('button', {
      name: 'Workflow actions',
    });

    await userEvent.click(menu);
    await userEvent.click(
      await screen.findByRole('menuitem', { name: 'Versions' })
    );
    expect(await screen.findByText('0001-a')).toBeInTheDocument();

    await userEvent.click(menu);
    await userEvent.click(
      await screen.findByRole('menuitem', { name: 'Hide versions' })
    );
    expect(screen.queryByText('0001-a')).not.toBeInTheDocument();
  });

  it('closes and reopens the progress panel', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByTestId('progress-panel');

    await user.click(
      screen.getByRole('button', { name: /close workflow progress/i })
    );
    expect(screen.queryByTestId('progress-panel')).toBeNull();

    await user.click(
      screen.getByRole('button', { name: /workflow progress/i })
    );
    expect(screen.getByTestId('progress-panel')).toBeInTheDocument();
  });

  it('switches executions through the run history dropdown', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByTestId('workflow-details-page');

    await user.click(screen.getByRole('button', { name: /run history/i }));
    await user.click(await screen.findByText('exec-old-1'));
    await user.click(screen.getByRole('tab', { name: 'Details' }));

    await waitFor(() =>
      expect(screen.getByTestId('execution-summary')).toHaveTextContent(
        'exec-old-1'
      )
    );
    await waitFor(() =>
      expect(screen.getByTestId('status-chip')).toHaveTextContent('Succeeded')
    );
  });

  it('honours a preselected execution from the URL', async () => {
    renderPage(
      '/sessions/SESSION_A92_XP_2024/workflow/wf-982-xk-11?exec=exec-old-1'
    );
    await screen.findByTestId('workflow-details-page');
    await userEvent.click(screen.getByRole('tab', { name: 'Details' }));

    await waitFor(() =>
      expect(screen.getByTestId('execution-summary')).toHaveTextContent(
        'exec-old-1'
      )
    );
  });

  it('renders the never-run state', async () => {
    executionsPayload = () => [];
    renderPage();
    await screen.findByTestId('workflow-details-page');

    expect(screen.getByTestId('status-chip')).toHaveTextContent('Not run');
    expect(screen.getByTestId('run-history-empty')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Follow running nodes' })).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('tab', { name: 'Details' }));
    expect(screen.getByTestId('execution-summary')).toHaveTextContent(
      'Not run'
    );
    expect(screen.getByTestId('workflow-node-data-ingress')).toHaveTextContent(
      'Waiting'
    );
    expect(screen.getByTestId('logs-body')).toHaveTextContent('No log output.');
    expect(MockEventSource.instances).toHaveLength(0);
  });

  it('streams live log entries into the terminal', async () => {
    renderPage();
    await screen.findByTestId('workflow-details-page');

    await waitFor(() =>
      expect(MockEventSource.byUrl('/logs/entries')).toBeDefined()
    );
    const logStream = MockEventSource.byUrl('/logs/entries');
    logStream.open();
    logStream.emit({
      executionId: 'exec-091-qp-55',
      pluginId: 'vectorize-batch',
      pluginName: 'vector-engine-v2',
      stream: 'STDOUT',
      message: 'Processing batch 2/45...',
      level: 'INFO',
      timestamp: '2026-07-26T14:22:18Z',
    });

    await waitFor(() =>
      expect(screen.getByTestId('logs-body')).toHaveTextContent(
        'Processing batch 2/45...'
      )
    );
    expect(screen.getByTestId('streaming-pill')).toBeInTheDocument();
  });

  it('shows the error state when the graph fails to load', async () => {
    server.use(
      http.get('/api/sessions/:sessionId/workflows/:workflowId/graph', () =>
        HttpResponse.json({
          timestamp: 't',
          status: 500,
          message: 'boom',
          error: 'Internal error',
          path: '/graph',
        })
      )
    );
    renderPage();

    expect(
      await screen.findByText('Failed to load workflow')
    ).toBeInTheDocument();
  });
});
