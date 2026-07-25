// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { WorkflowGrid } from '../WorkflowGrid';
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

describe('WorkflowGrid', () => {
  it('renders workflow count in header', () => {
    render(
      <MemoryRouter>
        <WorkflowGrid
          sessionId="sess-123"
          workflows={mockWorkflows}
          filter=""
          onFilterChange={() => {}}
        />
      </MemoryRouter>
    );

    expect(screen.getByText('Associated Workflows (2)')).toBeInTheDocument();
  });

  it('renders all workflows', () => {
    render(
      <MemoryRouter>
        <WorkflowGrid
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
        <WorkflowGrid
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
        <WorkflowGrid
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
        <WorkflowGrid sessionId="sess-123" workflows={[]} filter="" onFilterChange={() => {}} />
      </MemoryRouter>
    );

    expect(screen.getByText('No workflows')).toBeInTheDocument();
  });

  it('shows no match message when filter returns empty', () => {
    render(
      <MemoryRouter>
        <WorkflowGrid
          sessionId="sess-123"
          workflows={mockWorkflows}
          filter="nonexistent"
          onFilterChange={() => {}}
        />
      </MemoryRouter>
    );

    expect(screen.getByText("No workflows match your filter")).toBeInTheDocument();
  });

  it('calls onFilterChange when input changes', () => {
    const onFilterChange = vi.fn();
    render(
      <MemoryRouter>
        <WorkflowGrid
          sessionId="sess-123"
          workflows={mockWorkflows}
          filter=""
          onFilterChange={onFilterChange}
        />
      </MemoryRouter>
    );

    const input = screen.getByPlaceholderText('Filter workflows...');
    fireEvent.change(input, { target: { value: 'test' } });

    expect(onFilterChange).toHaveBeenCalledWith('test');
  });
});
