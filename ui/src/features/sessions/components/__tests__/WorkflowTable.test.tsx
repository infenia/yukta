// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { MemoryRouter, useLocation } from 'react-router';
import userEvent from '@testing-library/user-event';
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
      <MemoryRouter>
        <WorkflowTable
          sessionId="sess-123"
          workflows={mockWorkflows}
          filter=""
          onFilterChange={() => {}}
        />
      </MemoryRouter>
    );

    expect(
      screen.getByRole('heading', { name: 'Workflows' })
    ).toBeInTheDocument();
    expect(screen.getByText('2')).toBeInTheDocument();
  });

  it('renders all workflows', () => {
    render(
      <MemoryRouter>
        <WorkflowTable
          sessionId="sess-123"
          workflows={mockWorkflows}
          filter=""
          onFilterChange={() => {}}
        />
      </MemoryRouter>
    );

    expect(screen.getByText('wf1')).toBeInTheDocument();
    expect(screen.getByText('wf2')).toBeInTheDocument();
  });

  it('filters workflows by name', () => {
    render(
      <MemoryRouter>
        <WorkflowTable
          sessionId="sess-123"
          workflows={mockWorkflows}
          filter="wf1"
          onFilterChange={() => {}}
        />
      </MemoryRouter>
    );

    expect(screen.getByText('wf1')).toBeInTheDocument();
    expect(screen.queryByText('wf2')).not.toBeInTheDocument();
  });

  it('filters workflows by description', () => {
    render(
      <MemoryRouter>
        <WorkflowTable
          sessionId="sess-123"
          workflows={mockWorkflows}
          filter="Second"
          onFilterChange={() => {}}
        />
      </MemoryRouter>
    );

    expect(screen.queryByText('wf1')).not.toBeInTheDocument();
    expect(screen.getByText('wf2')).toBeInTheDocument();
  });

  it('shows empty state when no workflows', () => {
    render(
      <MemoryRouter>
        <WorkflowTable
          sessionId="sess-123"
          workflows={[]}
          filter=""
          onFilterChange={() => {}}
        />
      </MemoryRouter>
    );

    expect(screen.getByText('No workflows')).toBeInTheDocument();
  });

  it('shows no match message when filter returns empty', () => {
    render(
      <MemoryRouter>
        <WorkflowTable
          sessionId="sess-123"
          workflows={mockWorkflows}
          filter="nonexistent"
          onFilterChange={() => {}}
        />
      </MemoryRouter>
    );

    expect(
      screen.getByText('No workflows match your filter')
    ).toBeInTheDocument();
  });

  it('calls onFilterChange when input changes', () => {
    const onFilterChange = vi.fn();
    render(
      <MemoryRouter>
        <WorkflowTable
          sessionId="sess-123"
          workflows={mockWorkflows}
          filter=""
          onFilterChange={onFilterChange}
        />
      </MemoryRouter>
    );

    const input = screen.getByLabelText('Filter workflows');
    fireEvent.change(input, { target: { value: 'test' } });

    expect(onFilterChange).toHaveBeenCalledWith('test');
  });

  it('shows each workflow as a row: link, size and last run', () => {
    render(
      <MemoryRouter>
        <WorkflowTable
          sessionId="sess 1"
          workflows={mockWorkflows}
          filter=""
          onFilterChange={() => {}}
        />
      </MemoryRouter>
    );
    const row = screen.getByTestId('workflow-row-wf1');
    expect(screen.getByRole('link', { name: 'wf1' })).toHaveAttribute(
      'href',
      '/sessions/sess%201/workflow/wf1'
    );
    expect(row).toHaveTextContent('5 nodes · 4 edges');
    expect(row).toHaveTextContent('Succeeded');
    expect(screen.getByTestId('workflow-row-wf2')).toHaveTextContent('Running');
  });

  it('leaves out an empty description', () => {
    render(
      <MemoryRouter>
        <WorkflowTable
          sessionId="s"
          workflows={[{ ...mockWorkflows[0], description: '' }]}
          filter=""
          onFilterChange={() => {}}
        />
      </MemoryRouter>
    );
    expect(
      screen.getByTestId('workflow-row-wf1').querySelector('p')
    ).toBeNull();
  });

  it('opens the editor from the row menu', async () => {
    const user = userEvent.setup();
    function Where() {
      return <output data-testid="where">{useLocation().pathname}</output>;
    }
    render(
      <MemoryRouter>
        <Where />
        <WorkflowTable
          sessionId="s1"
          workflows={mockWorkflows}
          filter=""
          onFilterChange={() => {}}
        />
      </MemoryRouter>
    );
    await user.click(
      screen.getByRole('button', { name: 'Actions for workflow wf2' })
    );
    await user.click(await screen.findByRole('menuitem', { name: 'Edit' }));
    expect(screen.getByTestId('where')).toHaveTextContent(
      '/sessions/s1/workflow/wf2/edit'
    );
  });
});
