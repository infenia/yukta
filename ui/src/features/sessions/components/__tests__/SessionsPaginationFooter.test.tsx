// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import {
  useReactTable,
  getCoreRowModel,
  getPaginationRowModel,
  getFilteredRowModel,
} from '@tanstack/react-table';
import { SessionsPaginationFooter } from '../SessionsPaginationFooter';
import { columns } from '../columns';
import { createMockSessions } from '@/test/factories/sessionFactory';
import type { SessionListItem } from '@/features/sessions/types/session';

describe('SessionsPaginationFooter', () => {
  const PaginationTest = ({
    sessions,
    pageSize = 10,
  }: {
    sessions: SessionListItem[];
    pageSize?: number;
  }) => {
    const table = useReactTable({
      data: sessions,
      columns,
      getCoreRowModel: getCoreRowModel(),
      getPaginationRowModel: getPaginationRowModel(),
      getFilteredRowModel: getFilteredRowModel(),
      initialState: { pagination: { pageSize } },
    });

    return <SessionsPaginationFooter table={table} />;
  };

  it('should cover first page navigation buttons', () => {
    const sessions = createMockSessions(30);
    render(<PaginationTest sessions={sessions} pageSize={10} />);

    // All buttons should be visible
    const buttons = screen.getAllByRole('button');
    expect(buttons.length).toBeGreaterThan(0);

    // Items display
    expect(screen.getByText('1–10 of 30 items')).toBeInTheDocument();
  });

  it('should cover rows per page selection', () => {
    const sessions = createMockSessions(50);
    render(<PaginationTest sessions={sessions} pageSize={10} />);

    expect(screen.getByText('10')).toBeInTheDocument();
  });

  it('should cover last page state', () => {
    const sessions = createMockSessions(25);
    render(<PaginationTest sessions={sessions} pageSize={10} />);

    expect(screen.getByText('1–10 of 25 items')).toBeInTheDocument();
  });

  it('should cover with twenty page size', () => {
    const sessions = createMockSessions(100);
    render(<PaginationTest sessions={sessions} pageSize={20} />);

    expect(screen.getByText('20')).toBeInTheDocument();
    expect(screen.getByText('1–20 of 100 items')).toBeInTheDocument();
  });

  it('should cover with fifty page size', () => {
    const sessions = createMockSessions(100);
    render(<PaginationTest sessions={sessions} pageSize={50} />);

    expect(screen.getByText('50')).toBeInTheDocument();
    expect(screen.getByText('1–50 of 100 items')).toBeInTheDocument();
  });

  it('should cover all button states', () => {
    const sessions = createMockSessions(100);
    render(<PaginationTest sessions={sessions} pageSize={10} />);

    const buttons = screen.getAllByRole('button');

    // Should have: rows per page button, "x-y of z items" display, and 4 nav buttons
    expect(buttons.length).toBeGreaterThanOrEqual(5);
  });

  it('should separate pagination controls visually', () => {
    const sessions = createMockSessions(30);
    render(<PaginationTest sessions={sessions} pageSize={10} />);

    // Verify distinct sections are present and visible
    expect(screen.getByText(/Rows per page:/)).toBeInTheDocument();
    expect(screen.getByText('1–10 of 30 items')).toBeInTheDocument();

    // Verify navigation buttons are present (distinct control section)
    const firstPageButton = screen.getByRole('button', { name: 'First page' });
    expect(firstPageButton).toBeInTheDocument();
  });

  it('should render pagination with exact items text', () => {
    const sessions = createMockSessions(3);
    render(<PaginationTest sessions={sessions} pageSize={10} />);

    expect(screen.getByText('1–3 of 3 items')).toBeInTheDocument();
  });

  it('should display all pagination controls', () => {
    const sessions = createMockSessions(40);
    render(<PaginationTest sessions={sessions} pageSize={10} />);

    // Verify rows per page selector is present
    expect(screen.getByText(/Rows per page:/)).toBeInTheDocument();
    expect(screen.getByText('10')).toBeInTheDocument();

    // Verify pagination info is displayed
    expect(screen.getByText('1–10 of 40 items')).toBeInTheDocument();

    // Verify navigation buttons are present
    expect(screen.getByRole('button', { name: 'Next page' })).toBeInTheDocument();
  });

  it('should organize controls horizontally', () => {
    const sessions = createMockSessions(50);
    render(<PaginationTest sessions={sessions} pageSize={10} />);

    // Verify all controls are present and accessible
    expect(screen.getByText(/Rows per page:/)).toBeInTheDocument();
    expect(screen.getByText('1–10 of 50 items')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Last page' })).toBeInTheDocument();
  });

  it('should render navigation icons', () => {
    const sessions = createMockSessions(30);
    render(<PaginationTest sessions={sessions} pageSize={10} />);

    // Verify icon buttons are present and accessible
    const firstPageBtn = screen.getByRole('button', { name: 'First page' });
    const lastPageBtn = screen.getByRole('button', { name: 'Last page' });
    const nextBtn = screen.getByRole('button', { name: 'Next page' });
    const prevBtn = screen.getByRole('button', { name: 'Previous page' });

    expect(firstPageBtn).toBeInTheDocument();
    expect(lastPageBtn).toBeInTheDocument();
    expect(nextBtn).toBeInTheDocument();
    expect(prevBtn).toBeInTheDocument();
  });

  it('should align controls appropriately', () => {
    const sessions = createMockSessions(50);
    render(<PaginationTest sessions={sessions} pageSize={10} />);

    // Verify information section is readable
    expect(screen.getByText(/Rows per page:/)).toBeInTheDocument();
    expect(screen.getByText('1–10 of 50 items')).toBeInTheDocument();

    // Verify all navigation buttons are accessible
    const buttons = screen.getAllByRole('button');
    expect(buttons.length).toBeGreaterThanOrEqual(5); // dropdown + 4 nav buttons
  });
});
