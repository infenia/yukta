// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi, beforeAll, afterAll } from 'vitest';
import {
  render,
  screen,
  fireEvent,
  within,
  waitFor,
} from '@testing-library/react';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import { QueryClientProvider } from '@tanstack/react-query';
import { createTestQueryClient } from '@/test/utils/testUtils';
import { WorkflowTable } from '../WorkflowTable';
import type { WorkflowSummary } from '../../types/session';

const mockWorkflows: WorkflowSummary[] = [
  {
    workflowId: 'wf1',
    description: 'First Workflow',
    nodeCount: 5,
    edgeCount: 4,
    status: 'SUCCESS',
  },
  {
    workflowId: 'wf2',
    description: 'Second Workflow',
    nodeCount: 3,
    edgeCount: 2,
    status: 'RUNNING',
  },
];

describe('WorkflowTable', () => {
  it('renders workflow count in header', () => {
    render(
      <QueryClientProvider client={createTestQueryClient()}>
        <MemoryRouter>
          <WorkflowTable
            sessionId="sess-123"
            workflows={mockWorkflows}
            filter=""
            onFilterChange={() => {}}
          />
        </MemoryRouter>
      </QueryClientProvider>
    );

    expect(
      screen.getByRole('heading', { name: 'Workflows' })
    ).toBeInTheDocument();
    expect(screen.getByText('2')).toBeInTheDocument();
  });

  it('renders all workflows', () => {
    render(
      <QueryClientProvider client={createTestQueryClient()}>
        <MemoryRouter>
          <WorkflowTable
            sessionId="sess-123"
            workflows={mockWorkflows}
            filter=""
            onFilterChange={() => {}}
          />
        </MemoryRouter>
      </QueryClientProvider>
    );

    expect(screen.getByText('wf1')).toBeInTheDocument();
    expect(screen.getByText('wf2')).toBeInTheDocument();
  });

  it('filters workflows by name', () => {
    render(
      <QueryClientProvider client={createTestQueryClient()}>
        <MemoryRouter>
          <WorkflowTable
            sessionId="sess-123"
            workflows={mockWorkflows}
            filter="wf1"
            onFilterChange={() => {}}
          />
        </MemoryRouter>
      </QueryClientProvider>
    );

    expect(screen.getByText('wf1')).toBeInTheDocument();
    expect(screen.queryByText('wf2')).not.toBeInTheDocument();
  });

  it('filters workflows by description', () => {
    render(
      <QueryClientProvider client={createTestQueryClient()}>
        <MemoryRouter>
          <WorkflowTable
            sessionId="sess-123"
            workflows={mockWorkflows}
            filter="Second"
            onFilterChange={() => {}}
          />
        </MemoryRouter>
      </QueryClientProvider>
    );

    expect(screen.queryByText('wf1')).not.toBeInTheDocument();
    expect(screen.getByText('wf2')).toBeInTheDocument();
  });

  it('shows empty state when no workflows', () => {
    render(
      <QueryClientProvider client={createTestQueryClient()}>
        <MemoryRouter>
          <WorkflowTable
            sessionId="sess-123"
            workflows={[]}
            filter=""
            onFilterChange={() => {}}
          />
        </MemoryRouter>
      </QueryClientProvider>
    );

    expect(screen.getByText('No workflows')).toBeInTheDocument();
  });

  it('shows no match message when filter returns empty', () => {
    render(
      <QueryClientProvider client={createTestQueryClient()}>
        <MemoryRouter>
          <WorkflowTable
            sessionId="sess-123"
            workflows={mockWorkflows}
            filter="nonexistent"
            onFilterChange={() => {}}
          />
        </MemoryRouter>
      </QueryClientProvider>
    );

    expect(
      screen.getByText('No workflows match your filter')
    ).toBeInTheDocument();
  });

  it('calls onFilterChange when input changes', () => {
    const onFilterChange = vi.fn();
    render(
      <QueryClientProvider client={createTestQueryClient()}>
        <MemoryRouter>
          <WorkflowTable
            sessionId="sess-123"
            workflows={mockWorkflows}
            filter=""
            onFilterChange={onFilterChange}
          />
        </MemoryRouter>
      </QueryClientProvider>
    );

    const input = screen.getByLabelText('Filter workflows');
    fireEvent.change(input, { target: { value: 'test' } });

    expect(onFilterChange).toHaveBeenCalledWith('test');
  });

  it('shows each workflow as a row: size and last run', () => {
    render(
      <QueryClientProvider client={createTestQueryClient()}>
        <MemoryRouter>
          <WorkflowTable
            sessionId="sess 1"
            workflows={mockWorkflows}
            filter=""
            onFilterChange={() => {}}
          />
        </MemoryRouter>
      </QueryClientProvider>
    );
    const row = screen.getByTestId('workflow-row-wf1');
    expect(row).toHaveTextContent('5 nodes · 4 edges');
    // Last run is icon-only: the label lives in aria-label, not in the text.
    expect(within(row).getByLabelText('Succeeded')).toBeInTheDocument();
    expect(row).not.toHaveTextContent('Succeeded');
    const running = screen.getByTestId('workflow-row-wf2');
    expect(within(running).getByLabelText('Running')).toBeInTheDocument();
    expect(running).not.toHaveTextContent('Running');
  });

  it('leaves out an empty description', () => {
    render(
      <QueryClientProvider client={createTestQueryClient()}>
        <MemoryRouter>
          <WorkflowTable
            sessionId="s"
            workflows={[{ ...mockWorkflows[0], description: '' }]}
            filter=""
            onFilterChange={() => {}}
          />
        </MemoryRouter>
      </QueryClientProvider>
    );
    expect(
      screen.getByTestId('workflow-row-wf1').querySelector('p')
    ).toBeNull();
  });

  it('has no row menu; Edit and Delete are on the workflow page', () => {
    render(
      <QueryClientProvider client={createTestQueryClient()}>
        <MemoryRouter>
          <WorkflowTable
            sessionId="s1"
            workflows={mockWorkflows}
            filter=""
            onFilterChange={() => {}}
          />
        </MemoryRouter>
      </QueryClientProvider>
    );
    expect(
      screen.queryByRole('button', { name: 'Actions for workflow wf2' })
    ).not.toBeInTheDocument();
    expect(screen.queryByRole('menuitem')).not.toBeInTheDocument();
  });
});

const ok = (data: unknown) =>
  HttpResponse.json({
    timestamp: 't',
    status: 200,
    message: 'ok',
    data,
    path: '/',
  });

const server = setupServer(
  http.post('/api/workflow/start', () => ok({ executionId: 'new-run' }))
);

beforeAll(() => server.listen());
afterAll(() => server.close());

function LocationProbe() {
  const location = useLocation();
  return (
    <div data-testid="location">{location.pathname + location.search}</div>
  );
}

function renderNavigable() {
  return render(
    <QueryClientProvider client={createTestQueryClient()}>
      <MemoryRouter>
        <Routes>
          <Route
            path="*"
            element={
              <>
                <WorkflowTable
                  sessionId="sess 1"
                  workflows={mockWorkflows}
                  filter=""
                  onFilterChange={() => {}}
                />
                <LocationProbe />
              </>
            }
          />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  );
}

const currentPath = () => screen.getByTestId('location').textContent;

describe('WorkflowTable navigation', () => {
  it('opens the workflow page when a row is clicked', () => {
    renderNavigable();
    fireEvent.click(screen.getByTestId('workflow-row-wf1'));
    expect(currentPath()).toBe('/sessions/sess%201/workflow/wf1');
  });

  it('opens the workflow page from the keyboard with Enter', () => {
    renderNavigable();
    fireEvent.keyDown(screen.getByTestId('workflow-row-wf1'), {
      key: 'Enter',
    });
    expect(currentPath()).toBe('/sessions/sess%201/workflow/wf1');
  });

  it('opens the workflow page from the keyboard with Space', () => {
    renderNavigable();
    fireEvent.keyDown(screen.getByTestId('workflow-row-wf2'), { key: ' ' });
    expect(currentPath()).toBe('/sessions/sess%201/workflow/wf2');
  });

  it('ignores other keys on a workflow row', () => {
    renderNavigable();
    fireEvent.keyDown(screen.getByTestId('workflow-row-wf1'), {
      key: 'Tab',
    });
    expect(currentPath()).toBe('/');
  });

  it('keeps a click on the Run button from opening the row', () => {
    renderNavigable();
    fireEvent.click(screen.getByRole('button', { name: 'Run wf1' }));
    expect(currentPath()).toBe('/');
  });

  it('keeps keys pressed on the Run button from opening the row', () => {
    renderNavigable();
    fireEvent.keyDown(screen.getByRole('button', { name: 'Run wf1' }), {
      key: 'Enter',
    });
    expect(currentPath()).toBe('/');
  });

  it('opens the new run when a run started from the row succeeds', async () => {
    renderNavigable();
    fireEvent.click(screen.getByRole('button', { name: 'Run wf1' }));
    fireEvent.click(screen.getByRole('button', { name: 'Run' }));
    await waitFor(() =>
      expect(currentPath()).toBe('/sessions/sess%201/workflow/wf1?exec=new-run')
    );
  });
});
