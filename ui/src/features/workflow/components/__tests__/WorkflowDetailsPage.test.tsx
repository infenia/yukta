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
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
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

/** Stands for the Control page: shows the query it was opened with. */
function ControlSearch() {
  return <output data-testid="control-search">{useLocation().search}</output>;
}

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
          <Route path="/control" element={<ControlSearch />} />
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
    // a stage box is not a node: no sheet
    fireEvent.click(screen.getByTestId('stage-lint'));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();

    await user.click(screen.getByRole('radio', { name: 'All nodes' }));
    expect(
      await screen.findByTestId('workflow-node-lint.fix')
    ).toBeInTheDocument();
    expect(screen.queryByTestId('stage-lint')).not.toBeInTheDocument();
  });

  it('opens a sub-workflow from its node, and links a sub-workflow run back to its parent', async () => {
    server.use(
      http.get('/api/sessions/:sessionId/workflows/:workflowId/graph', () =>
        HttpResponse.json({
          timestamp: 't',
          status: 200,
          message: 'ok',
          data: createStagedGraph(),
          path: '/graph',
        })
      ),
      http.get('/api/executions/:executionId/tree', () =>
        HttpResponse.json({
          timestamp: 't',
          status: 200,
          message: 'ok',
          path: '/',
          data: [],
        })
      ),
      http.get('/api/executions/:executionId', ({ params }) =>
        HttpResponse.json({
          timestamp: 't',
          status: 200,
          message: 'ok',
          path: '/status',
          data:
            params.executionId === 'parent-run'
              ? createMockProgress({
                  executionId: 'parent-run',
                  workflowId: 'outer',
                })
              : createMockProgress({ parentExecutionId: 'parent-run' }),
        })
      )
    );
    renderPage('/sessions/SESSION_A92_XP_2024/workflow/gate?stages=all');
    expect(
      await screen.findByRole('link', { name: 'Open sub-workflow fix' })
    ).toHaveAttribute('href', '/sessions/SESSION_A92_XP_2024/workflow/fix');
    expect(
      await screen.findByRole('link', { name: 'Parent run' })
    ).toHaveAttribute(
      'href',
      '/sessions/SESSION_A92_XP_2024/workflow/outer?exec=parent-run'
    );
  });

  describe('node sheet', () => {
    const definition = () =>
      server.use(
        http.get('/api/sessions/:sessionId/workflows/:workflowId', () =>
          HttpResponse.json({
            timestamp: 't',
            status: 200,
            message: 'ok',
            path: '/workflow',
            data: {
              workflowId: 'wf-982-xk-11',
              nodes: [
                {
                  nodeId: 'vectorize-batch',
                  type: 'X',
                  config: { apiKey: '******', batch: 8 },
                },
              ],
            },
          })
        )
      );

    it('opens a clicked node with its masked config, and closes it', async () => {
      definition();
      const user = userEvent.setup();
      renderPage();
      // fireEvent: user-event's pointer events on the xyflow pane outlive the test
      fireEvent.click(
        await screen.findByTestId('workflow-node-vectorize-batch')
      );
      const sheet = await screen.findByRole('dialog', {
        name: 'vectorize-batch',
      });
      expect(await within(sheet).findByText(/"apiKey": "\*{6}"/)).toBeVisible();
      expect(
        within(sheet).getByText('No log lines from this node.')
      ).toBeVisible();

      await user.click(
        within(sheet).getByRole('button', { name: 'Close node details' })
      );
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    });

    it('keeps the page usable while a node is open: another node switches the panel, Escape closes it', async () => {
      definition();
      renderPage();
      fireEvent.click(
        await screen.findByTestId('workflow-node-vectorize-batch')
      );
      await screen.findByRole('dialog', { name: 'vectorize-batch' });
      // not modal: the page behind is neither hidden from nor inert to the user
      expect(screen.getByTestId('workflow-details-page')).not.toHaveAttribute(
        'aria-hidden'
      );

      fireEvent.click(screen.getByTestId('workflow-node-data-ingress'));
      expect(
        await screen.findByRole('dialog', { name: 'data-ingress' })
      ).toBeInTheDocument();

      await userEvent.setup().keyboard('{Escape}');
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    });

    it('opens from a shared ?node= link', async () => {
      definition();
      renderPage(
        '/sessions/SESSION_A92_XP_2024/workflow/wf-982-xk-11?node=vectorize-batch'
      );
      expect(
        await screen.findByRole('dialog', { name: 'vectorize-batch' })
      ).toBeInTheDocument();
    });

    it('ignores a ?node= that is not in the graph', async () => {
      renderPage(
        '/sessions/SESSION_A92_XP_2024/workflow/wf-982-xk-11?node=nope'
      );
      await screen.findByTestId('workflow-node-vectorize-batch');
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    });

    it('ignores other keys, and Enter pressed inside the node', async () => {
      renderPage();
      const card = await screen.findByTestId('workflow-node-vectorize-batch');
      fireEvent.keyDown(card, { key: 'a' });
      fireEvent.keyDown(must(card.firstElementChild), { key: 'Enter' });
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    });

    it('opens on Enter from a focused node', async () => {
      definition();
      renderPage();
      fireEvent.keyDown(
        await screen.findByTestId('workflow-node-vectorize-batch'),
        { key: 'Enter' }
      );
      expect(
        await screen.findByRole('dialog', { name: 'vectorize-batch' })
      ).toBeInTheDocument();
    });
  });

  it('switches between the graph and the timeline', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByTestId('workflow-node-vectorize-batch');

    await user.click(screen.getByRole('radio', { name: 'Timeline' }));
    expect(await screen.findByTestId('run-timeline')).toBeInTheDocument();
    expect(
      screen.queryByTestId('workflow-node-vectorize-batch')
    ).not.toBeInTheDocument();

    await user.click(screen.getByRole('radio', { name: 'Graph' }));
    expect(
      await screen.findByTestId('workflow-node-vectorize-batch')
    ).toBeInTheDocument();
  });

  it('follows the running nodes of a live run until told not to', async () => {
    const user = userEvent.setup();
    renderPage();
    const follow = await screen.findByRole('button', {
      name: 'Follow running nodes',
    });
    expect(follow).toHaveAttribute('aria-pressed', 'true');
    await user.click(follow);
    expect(follow).toHaveAttribute('aria-pressed', 'false');
  });

  it('finds a node from the canvas toolbar', async () => {
    const user = userEvent.setup();
    renderPage();
    const find = await screen.findByRole('searchbox', {
      name: 'Find node on canvas',
    });
    await user.type(find, 'vector');
    expect(find).toHaveValue('vector');
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

  it('bounds the page to the screen so a large run scrolls inside its panels', async () => {
    renderPage();
    const page = await screen.findByTestId('workflow-details-page');

    // jsdom has no layout: assert the bounded-height classes instead of measuring
    expect(page.className).toMatch(/(^|\s)sm:h-\[calc\(100dvh/);
    expect(page.className).not.toMatch(/min-h-\[calc/);
  });

  it('keeps the run picker and the progress toggle in its own toolbar', async () => {
    renderPage();
    await screen.findByTestId('workflow-details-page');

    const toolbar = must(
      screen.getByRole('heading', { name: 'wf-982-xk-11' }).parentElement
    );
    expect(toolbar.textContent).toContain('Runexec-091');
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

  it('links from the run to its control commands', async () => {
    renderPage();
    await userEvent.click(
      await screen.findByRole('button', { name: 'Workflow actions' })
    );
    await userEvent.click(
      await screen.findByRole('menuitem', {
        name: 'Control commands of this run',
      })
    );
    expect(await screen.findByTestId('control-search')).toHaveTextContent(
      /^\?execution=.+/
    );
  });

  it('closes and reopens the progress panel', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByTestId('progress-panel');
    const toggle = screen.getByRole('button', { name: 'Workflow progress' });
    expect(toggle).toHaveAttribute('aria-pressed', 'true');
    expect(toggle).toHaveAttribute('title', 'Hide progress panel');
    expect(toggle).toHaveTextContent('right_panel_close');

    await user.click(
      screen.getByRole('button', { name: /close workflow progress/i })
    );
    expect(screen.queryByTestId('progress-panel')).toBeNull();
    expect(toggle).toHaveAttribute('aria-pressed', 'false');
    expect(toggle).toHaveAttribute('title', 'Show progress panel');
    expect(toggle).toHaveTextContent('right_panel_open');

    await user.click(toggle);
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
    expect(
      screen.queryByRole('button', { name: 'Follow running nodes' })
    ).not.toBeInTheDocument();
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
