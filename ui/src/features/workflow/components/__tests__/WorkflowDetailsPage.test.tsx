// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeAll, afterAll, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router';
import { QueryClientProvider } from '@tanstack/react-query';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { WorkflowDetailsPage } from '../WorkflowDetailsPage';
import { BreadcrumbOverridesProvider } from '@/lib/breadcrumbOverrides';
import { createTestQueryClient, must } from '@/test/utils/testUtils';
import {
  createMockExecution,
  createMockGraph,
  createMockProgress,
} from '@/test/factories/workflowFactory';
import { installMockEventSource, MockEventSource } from '@/test/mocks/MockEventSource';

let executionsPayload = () => [
  createMockExecution(),
  createMockExecution({ executionId: 'exec-old-1', status: 'SUCCESS', endTime: '2026-07-25T10:00:00' }),
];

const server = setupServer(
  http.get('/api/sessions/:sessionId/workflows/:workflowId/graph', () =>
    HttpResponse.json({
      timestamp: 't', status: 200, message: 'ok', data: createMockGraph(), path: '/graph',
    })
  ),
  http.get('/api/sessions/:sessionId/workflows/:workflowId/executions', () =>
    HttpResponse.json({
      timestamp: 't', status: 200, message: 'ok', data: { executions: executionsPayload() }, path: '/executions',
    })
  ),
  http.get('/api/workflow/:sessionId/status/:executionId', ({ params }) =>
    HttpResponse.json({
      timestamp: 't',
      status: 200,
      message: 'ok',
      data: createMockProgress(
        params.executionId === 'exec-old-1'
          ? { executionId: 'exec-old-1', status: 'COMPLETED', endTime: '2026-07-25T10:05:00' }
          : {}
      ),
      path: '/status',
    })
  )
);

beforeAll(() => server.listen());
afterAll(() => server.close());

function renderPage(initialEntry = '/sessions/SESSION_A92_XP_2024/workflow/wf-982-xk-11') {
  return render(
    <QueryClientProvider client={createTestQueryClient()}>
      <BreadcrumbOverridesProvider>
        <MemoryRouter initialEntries={[initialEntry]}>
          <div id="header-actions" />
          <div id="breadcrumb-actions" />
          <Routes>
            <Route path="/sessions/:sessionId/workflow/:workflowId" element={<WorkflowDetailsPage />} />
          </Routes>
        </MemoryRouter>
      </BreadcrumbOverridesProvider>
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

  it('shows a loading state before data arrives', () => {
    renderPage();
    expect(screen.getByText('Loading workflow...')).toBeInTheDocument();
  });

  it('renders the full page for a running workflow', async () => {
    renderPage();

    expect(await screen.findByTestId('workflow-details-page')).toBeInTheDocument();
    expect(
      screen.getByRole('heading', { name: 'Supply_Chain_Optimizer_V2' })
    ).toBeInTheDocument();
    expect(screen.getByText('ID: wf-982-xk-11')).toBeInTheDocument();
    expect(screen.getByTestId('workflow-canvas')).toBeInTheDocument();
    expect(screen.getByTestId('progress-panel')).toBeInTheDocument();
    expect(screen.getByTestId('logs-panel')).toBeInTheDocument();

    await waitFor(() => expect(screen.getByTestId('status-chip')).toHaveTextContent('RUNNING'));
    expect(screen.getByTestId('workflow-node-data-ingress')).toHaveTextContent('Status: Done');
  });

  it('portals the progress toggle into the header and the run history into the breadcrumb bar', async () => {
    renderPage();
    await screen.findByTestId('workflow-details-page');

    const headerSlot = must(document.getElementById('header-actions'));
    expect(headerSlot.textContent).toContain('WORKFLOW PROGRESS');

    const breadcrumbSlot = must(document.getElementById('breadcrumb-actions'));
    expect(breadcrumbSlot.textContent).toContain('Run History:');
    expect(breadcrumbSlot.textContent).toContain('exec-091-qp-55');
  });

  it('closes and reopens the progress panel', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByTestId('progress-panel');

    await user.click(screen.getByRole('button', { name: /close workflow progress/i }));
    expect(screen.queryByTestId('progress-panel')).toBeNull();

    await user.click(screen.getByRole('button', { name: /workflow progress/i }));
    expect(screen.getByTestId('progress-panel')).toBeInTheDocument();
  });

  it('switches executions through the run history dropdown', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByTestId('workflow-details-page');

    await user.click(screen.getByRole('button', { name: /run history/i }));
    await user.click(await screen.findByText('exec-old-1'));

    await waitFor(() =>
      expect(screen.getByTestId('execution-summary')).toHaveTextContent('exec-old-1')
    );
    await waitFor(() => expect(screen.getByTestId('status-chip')).toHaveTextContent('COMPLETED'));
  });

  it('honours a preselected execution from the URL', async () => {
    renderPage('/sessions/SESSION_A92_XP_2024/workflow/wf-982-xk-11?exec=exec-old-1');
    await screen.findByTestId('workflow-details-page');

    await waitFor(() =>
      expect(screen.getByTestId('execution-summary')).toHaveTextContent('exec-old-1')
    );
  });

  it('renders the never-run state', async () => {
    executionsPayload = () => [];
    renderPage();
    await screen.findByTestId('workflow-details-page');

    expect(screen.getByTestId('status-chip')).toHaveTextContent('NOT RUN');
    expect(screen.getByTestId('run-history-empty')).toBeInTheDocument();
    expect(screen.getByTestId('execution-summary')).toHaveTextContent('NOT RUN');
    expect(screen.getByTestId('workflow-node-data-ingress')).toHaveTextContent('Status: Waiting');
    expect(screen.getByTestId('logs-body')).toHaveTextContent('No log output.');
    expect(MockEventSource.instances).toHaveLength(0);
  });

  it('streams live log entries into the terminal', async () => {
    renderPage();
    await screen.findByTestId('workflow-details-page');

    await waitFor(() => expect(MockEventSource.byUrl('/logs/entries')).toBeDefined());
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
      expect(screen.getByTestId('logs-body')).toHaveTextContent('Processing batch 2/45...')
    );
    expect(screen.getByTestId('streaming-pill')).toBeInTheDocument();
  });

  it('shows the error state when the graph fails to load', async () => {
    server.use(
      http.get('/api/sessions/:sessionId/workflows/:workflowId/graph', () =>
        HttpResponse.json({
          timestamp: 't', status: 500, message: 'boom', error: 'Internal error', path: '/graph',
        })
      )
    );
    renderPage();

    expect(await screen.findByText('Failed to load workflow')).toBeInTheDocument();
  });
});
