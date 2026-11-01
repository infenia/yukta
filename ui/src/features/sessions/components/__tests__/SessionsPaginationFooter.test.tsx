// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import type { LegacyReactTable } from '@tanstack/react-table/legacy';
import { SessionsPaginationFooter } from '../SessionsPaginationFooter';

const fakeTable = (opts: {
  rows?: number;
  pageIndex?: number;
  pageSize?: number;
  canPrev?: boolean;
  canNext?: boolean;
}) => {
  const table = {
    getState: () => ({
      pagination: {
        pageIndex: opts.pageIndex ?? 0,
        pageSize: opts.pageSize ?? 10,
      },
    }),
    getFilteredRowModel: () => ({ rows: new Array(opts.rows ?? 0) }),
    getCanPreviousPage: () => opts.canPrev ?? false,
    getCanNextPage: () => opts.canNext ?? false,
    setPageSize: vi.fn(),
    firstPage: vi.fn(),
    previousPage: vi.fn(),
    nextPage: vi.fn(),
    lastPage: vi.fn(),
  };
  return table as unknown as LegacyReactTable<never> & typeof table;
};

const renderFooter = (table: ReturnType<typeof fakeTable>) =>
  render(
    <SessionsPaginationFooter
      table={table as unknown as LegacyReactTable<never> as never}
    />
  );

describe('SessionsPaginationFooter', () => {
  it('shows 0–0 when there are no rows', () => {
    renderFooter(fakeTable({ rows: 0 }));
    expect(screen.getByText(/0–0 of 0 items/)).toBeInTheDocument();
  });

  it('shows the range of the current page', () => {
    renderFooter(fakeTable({ rows: 25, pageIndex: 1, pageSize: 10 }));
    expect(screen.getByText(/11–20 of 25 items/)).toBeInTheDocument();
  });

  it('enables only the directions that have a page', () => {
    renderFooter(fakeTable({ rows: 25, canPrev: false, canNext: true }));
    expect(screen.getByRole('button', { name: 'Previous page' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Next page' })).toBeEnabled();
  });

  it('moves between pages from the buttons', () => {
    const table = fakeTable({ rows: 25, canPrev: true, canNext: true });
    renderFooter(table);
    fireEvent.click(screen.getByRole('button', { name: 'First page' }));
    fireEvent.click(screen.getByRole('button', { name: 'Previous page' }));
    fireEvent.click(screen.getByRole('button', { name: 'Next page' }));
    fireEvent.click(screen.getByRole('button', { name: 'Last page' }));
    expect(table.firstPage).toHaveBeenCalled();
    expect(table.previousPage).toHaveBeenCalled();
    expect(table.nextPage).toHaveBeenCalled();
    expect(table.lastPage).toHaveBeenCalled();
  });

  it('changes the rows per page', async () => {
    const table = fakeTable({ rows: 25 });
    renderFooter(table);
    fireEvent.click(screen.getByRole('button', { name: /10/ }));
    fireEvent.click(await screen.findByRole('menuitem', { name: '20' }));
    expect(table.setPageSize).toHaveBeenCalledWith(20);
  });
});
