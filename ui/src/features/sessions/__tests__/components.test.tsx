// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { BrowserRouter } from 'react-router';
import {
  useReactTable,
  getCoreRowModel,
  getPaginationRowModel,
  getFilteredRowModel,
} from '@tanstack/react-table';
import { SessionsHeader } from '../components/SessionsHeader';
import { SessionsFilterBar } from '../components/SessionsFilterBar';
import { SessionsPaginationFooter } from '../components/SessionsPaginationFooter';
import { SessionRowActionsMenu } from '../components/SessionRowActionsMenu';
import { columns } from '../components/columns';
import type { SessionListItem } from '../types/session';

describe('SessionsHeader', () => {
  it('should render Sessions title', () => {
    render(<SessionsHeader sessionCount={5} />);
    expect(screen.getByRole('heading', { name: /Sessions/ })).toBeInTheDocument();
  });

  it('should display session count - singular', () => {
    const { container } = render(<SessionsHeader sessionCount={1} />);
    const p = container.querySelector('p');
    expect(p?.textContent).toContain('1 session');
    expect(p?.textContent).toContain('found.');
  });

  it('should display session count - plural', () => {
    const { container } = render(<SessionsHeader sessionCount={5} />);
    const p = container.querySelector('p');
    expect(p?.textContent).toContain('5 sessions');
    expect(p?.textContent).toContain('found.');
  });

  it('should handle zero sessions', () => {
    const { container } = render(<SessionsHeader sessionCount={0} />);
    const p = container.querySelector('p');
    expect(p?.textContent).toContain('0 sessions');
    expect(p?.textContent).toContain('found.');
  });
});

describe('SessionsFilterBar', () => {
  it('should render search input', () => {
    render(
      <SessionsFilterBar
        data={[]}
        globalFilter=""
        onGlobalFilterChange={() => {}}
        onReset={() => {}}
      />
    );
    expect(screen.getByPlaceholderText(/search by name/i)).toBeInTheDocument();
  });

  it('should render reset button', () => {
    render(
      <SessionsFilterBar
        data={[]}
        globalFilter=""
        onGlobalFilterChange={() => {}}
        onReset={() => {}}
      />
    );
    // Reset button is now icon-only with aria-label
    expect(screen.getByLabelText('Reset filters')).toBeInTheDocument();
  });

  it('should call onGlobalFilterChange when typing in search', async () => {
    const user = userEvent.setup();
    const handleChange = vi.fn();

    render(
      <SessionsFilterBar
        data={[]}
        globalFilter=""
        onGlobalFilterChange={handleChange}
        onReset={() => {}}
      />
    );

    const input = screen.getByPlaceholderText(/search by name/i);
    await user.type(input, 'test');

    expect(handleChange).toHaveBeenCalled();
  });

  it('should call onReset when reset button clicked', async () => {
    const user = userEvent.setup();
    const handleReset = vi.fn();

    render(
      <SessionsFilterBar
        data={[]}
        globalFilter=""
        onGlobalFilterChange={() => {}}
        onReset={handleReset}
      />
    );

    const resetButton = screen.getByLabelText('Reset filters');
    await user.click(resetButton);

    expect(handleReset).toHaveBeenCalled();
  });

  it('should display current globalFilter value', () => {
    render(
      <SessionsFilterBar
        data={[]}
        globalFilter="test"
        onGlobalFilterChange={() => {}}
        onReset={() => {}}
      />
    );

    const input = screen.getByPlaceholderText(/search by name/i) as HTMLInputElement;
    expect(input.value).toBe('test');
  });
});

describe('SessionsPaginationFooter', () => {
  const mockSessions: SessionListItem[] = [
    {
      sessionId: 'session-1',
      name: 'Session 1',
      description: 'Desc 1',
      initiator: 'user@example.com',
      tags: [],
      projectPath: '/path',
      workflowCount: 1,
    },
  ];

  const TableWrapper = () => {
    const table = useReactTable({
      data: mockSessions,
      columns,
      getCoreRowModel: getCoreRowModel(),
      getPaginationRowModel: getPaginationRowModel(),
      getFilteredRowModel: getFilteredRowModel(),
      initialState: { pagination: { pageSize: 10 } },
    });

    return <SessionsPaginationFooter table={table} />;
  };

  it('should render rows per page selector', () => {
    render(<TableWrapper />);

    expect(screen.getByText(/Rows per page:/)).toBeInTheDocument();
  });

  it('should render pagination info', () => {
    render(<TableWrapper />);

    expect(screen.getByText(/items/)).toBeInTheDocument();
  });

  it('should render current page size', () => {
    render(<TableWrapper />);

    expect(screen.getByText('10')).toBeInTheDocument();
  });

  it('should render pagination buttons', () => {
    render(<TableWrapper />);

    const buttons = screen.getAllByRole('button');
    expect(buttons.length).toBeGreaterThan(0);
  });

  it('should show item count text', () => {
    render(<TableWrapper />);

    expect(screen.getByText(/of 1 items/)).toBeInTheDocument();
  });

  it('should display correct pagination format with start and end', () => {
    render(<TableWrapper />);

    // Verify the pagination text shows correct format
    const paginationText = screen.getByText(/of 1 items/);
    expect(paginationText).toBeInTheDocument();
    expect(paginationText.textContent).toMatch(/–/); // En-dash between start and end
  });
});

describe('SessionRowActionsMenu', () => {
  const mockSession: SessionListItem = {
    sessionId: 'session-1',
    name: 'Test Session',
    description: 'Test Description',
    initiator: 'user@example.com',
    tags: ['tag1'],
    projectPath: '/path/to/project',
    workflowCount: 3,
  };

  it('should render action menu button with more_vert icon', () => {
    render(
      <BrowserRouter>
        <SessionRowActionsMenu session={mockSession} />
      </BrowserRouter>
    );

    const icon = screen.getByText('more_vert');
    expect(icon).toBeInTheDocument();
  });

  it('should render dropdown menu trigger', () => {
    render(
      <BrowserRouter>
        <SessionRowActionsMenu session={mockSession} />
      </BrowserRouter>
    );

    const trigger = document.querySelector('[data-slot="dropdown-menu-trigger"]');
    expect(trigger).toBeInTheDocument();
  });

  it('should render with correct session data', () => {
    const { container } = render(
      <BrowserRouter>
        <SessionRowActionsMenu session={mockSession} />
      </BrowserRouter>
    );

    expect(container.textContent).toContain('more_vert');
  });

});
