// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useState } from 'react';
import { useNavigate } from 'react-router';
import {
  useReactTable,
  getCoreRowModel,
  getFilteredRowModel,
  getPaginationRowModel,
  flexRender,
  type ColumnFiltersState,
} from '@tanstack/react-table';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { useSessionSummaries } from '../hooks/useSessionSummaries';
import { columns } from './columns';
import { SessionsHeader } from './SessionsHeader';
import { SessionsFilterBar } from './SessionsFilterBar';
import { SessionsPaginationFooter } from './SessionsPaginationFooter';

export function SessionListPage() {
  const navigate = useNavigate();
  const { data: sessions = [], isLoading, error } = useSessionSummaries();
  const [globalFilter, setGlobalFilter] = useState('');
  const [columnFilters, setColumnFilters] = useState<ColumnFiltersState>([]);

  // React Compiler skips memoizing useReactTable (headless API returns unstable functions); accepted
  // eslint-disable-next-line react-hooks/incompatible-library
  const table = useReactTable({
    data: sessions,
    columns,
    getCoreRowModel: getCoreRowModel(),
    getFilteredRowModel: getFilteredRowModel(),
    getPaginationRowModel: getPaginationRowModel(),
    state: {
      globalFilter,
      columnFilters,
    },
    onGlobalFilterChange: setGlobalFilter,
    onColumnFiltersChange: setColumnFilters,
    globalFilterFn: (row, _columnId, filterValue) => {
      const { name, sessionId } = row.original;
      return (
        name.toLowerCase().includes(filterValue.toLowerCase()) ||
        sessionId.toLowerCase().includes(filterValue.toLowerCase())
      );
    },
    initialState: {
      pagination: {
        pageSize: 10,
      },
    },
  });

  if (isLoading) {
    return (
      <div className="flex flex-1 items-center justify-center">
        <p className="text-on-surface-variant">Loading sessions...</p>
      </div>
    );
  }

  if (error) {
    return (
      <div className="flex flex-1 items-center justify-center">
        <p className="text-error">Failed to load sessions</p>
      </div>
    );
  }

  const handleReset = () => {
    setGlobalFilter('');
    setColumnFilters([]);
    table.setPageIndex(0);
  };

  return (
    <div className="flex flex-1 flex-col gap-0 bg-background">
      <div className="w-full">
        <SessionsHeader sessionCount={sessions.length} />

        <SessionsFilterBar
          data={sessions}
          globalFilter={globalFilter}
          onGlobalFilterChange={setGlobalFilter}
          onReset={handleReset}
        />

        <div className="px-6 pb-12">
          <div className="border border-outline-variant bg-surface-container">
            <Table>
              <TableHeader>
                {table.getHeaderGroups().map((headerGroup) => (
                  <TableRow
                    key={headerGroup.id}
                    className="border-b border-outline-variant bg-surface-container-high hover:bg-surface-container-high"
                  >
                    {headerGroup.headers.map((header) => (
                      <TableHead
                        key={header.id}
                        className="h-10 text-xs font-semibold tracking-wider uppercase"
                      >
                        {header.isPlaceholder
                          ? null
                          : flexRender(header.column.columnDef.header, header.getContext())}
                      </TableHead>
                    ))}
                  </TableRow>
                ))}
              </TableHeader>
              <TableBody>
                {table.getRowModel().rows.length === 0 ? (
                  <TableRow className="border-b border-outline-variant hover:bg-surface-container-high">
                    <TableCell colSpan={columns.length} className="py-8 text-center">
                      <p className="text-body-md text-on-surface-variant">No sessions yet</p>
                    </TableCell>
                  </TableRow>
                ) : (
                  table.getRowModel().rows.map((row, idx) => (
                    <TableRow
                      key={row.id}
                      tabIndex={0}
                      onClick={() => navigate(`/sessions/${row.original.sessionId}`)}
                      onKeyDown={(e) => {
                        if (e.key === 'Enter' || e.key === ' ') {
                          e.preventDefault();
                          navigate(`/sessions/${row.original.sessionId}`);
                        }
                      }}
                      className={`cursor-pointer border-b border-outline-variant py-3 hover:bg-surface-container-high focus-visible:outline focus-visible:outline-2 focus-visible:outline-primary ${
                        idx % 2 === 1 ? 'bg-surface-container-low' : ''
                      }`}
                    >
                      {row.getVisibleCells().map((cell) => (
                        <TableCell key={cell.id} className="px-4">
                          {flexRender(cell.column.columnDef.cell, cell.getContext())}
                        </TableCell>
                      ))}
                    </TableRow>
                  ))
                )}
              </TableBody>
            </Table>

            <SessionsPaginationFooter table={table} />
          </div>
        </div>
      </div>
    </div>
  );
}
