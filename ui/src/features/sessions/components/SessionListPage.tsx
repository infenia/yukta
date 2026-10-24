// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { buttonVariants } from '@/components/ui/button';
import {
  useLegacyTable,
  getCoreRowModel,
  getFilteredRowModel,
  getPaginationRowModel,
} from '@tanstack/react-table/legacy';
import { flexRender, type ColumnFiltersState } from '@tanstack/react-table';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableFrame,
  TableRow,
} from '@/components/ui/table';
import { StateMessage } from '@/components/common/StateMessage';
import { useSessionSummaries } from '../hooks/useSessionSummaries';
import { columns } from './columns';
import { SessionsHeader } from './SessionsHeader';
import { SessionsFilterBar } from './SessionsFilterBar';
import { SessionsPaginationFooter } from './SessionsPaginationFooter';

export function SessionListPage() {
  const navigate = useNavigate();
  const {
    data: sessions = [],
    isLoading,
    error,
    refetch,
  } = useSessionSummaries();
  const [globalFilter, setGlobalFilter] = useState('');
  const [columnFilters, setColumnFilters] = useState<ColumnFiltersState>([]);

  // React Compiler skips memoizing useLegacyTable (headless API returns unstable functions); accepted
  // eslint-disable-next-line react-hooks/incompatible-library
  const table = useLegacyTable({
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
    globalFilterFn: (row: any, _columnId: string, filterValue: string) => {
      const { name, sessionId } = row.original;
      return (
        name.toLowerCase().includes(filterValue.toLowerCase()) ||
        sessionId.toLowerCase().includes(filterValue.toLowerCase())
      );
    },
    initialState: {
      pagination: {
        pageIndex: 0,
        pageSize: 10,
      },
    },
  });

  if (isLoading) {
    return (
      <div className="flex flex-1 items-center justify-center">
        <p className="text-body-md text-on-surface-variant">
          Loading sessions...
        </p>
      </div>
    );
  }

  if (error) {
    return (
      <div className="flex flex-1 items-center justify-center p-6">
        <StateMessage
          tone="error"
          title="Failed to load sessions"
          onRetry={() => void refetch()}
        >
          Check that the Yukta server is running, then retry.
        </StateMessage>
      </div>
    );
  }

  const handleReset = () => {
    setGlobalFilter('');
    setColumnFilters([]);
    table.setPageIndex(0);
  };

  return (
    <div className="bg-background flex flex-1 flex-col gap-0">
      <div>
        <SessionsHeader
          sessionCount={sessions.length}
          actions={
            <Link
              to="/sessions/new"
              className={buttonVariants({
                variant: 'outline',
                size: 'default',
              })}
            >
              <span className="material-symbols-outlined text-base">add</span>
              New session
            </Link>
          }
        />

        <SessionsFilterBar
          data={sessions}
          globalFilter={globalFilter}
          onGlobalFilterChange={setGlobalFilter}
          onReset={handleReset}
        />

        <div className="px-4 pb-8 sm:px-6">
          <TableFrame>
            <Table>
              <TableHeader>
                {table.getHeaderGroups().map((headerGroup: any) => (
                  <TableRow key={headerGroup.id}>
                    {headerGroup.headers.map((header: any) => (
                      <TableHead key={header.id} className="px-4">
                        {header.isPlaceholder
                          ? null
                          : flexRender(
                              header.column.columnDef.header,
                              header.getContext()
                            )}
                      </TableHead>
                    ))}
                  </TableRow>
                ))}
              </TableHeader>
              <TableBody>
                {table.getRowModel().rows.length === 0 ? (
                  <TableRow className="hover:bg-transparent">
                    <TableCell
                      colSpan={columns.length}
                      className="py-8 text-center"
                    >
                      <p className="text-body-md text-on-surface-variant">
                        No sessions yet
                      </p>
                    </TableCell>
                  </TableRow>
                ) : (
                  table.getRowModel().rows.map((row: any) => (
                    <TableRow
                      key={row.id}
                      tabIndex={0}
                      onClick={() =>
                        navigate(`/sessions/${row.original.sessionId}`)
                      }
                      onKeyDown={(e) => {
                        if (e.key === 'Enter' || e.key === ' ') {
                          e.preventDefault();
                          navigate(`/sessions/${row.original.sessionId}`);
                        }
                      }}
                      className="cursor-pointer focus-visible:-outline-offset-2"
                    >
                      {row.getVisibleCells().map((cell: any) => (
                        <TableCell key={cell.id} className="px-4">
                          {flexRender(
                            cell.column.columnDef.cell,
                            cell.getContext()
                          )}
                        </TableCell>
                      ))}
                    </TableRow>
                  ))
                )}
              </TableBody>
            </Table>

            <SessionsPaginationFooter table={table} />
          </TableFrame>
        </div>
      </div>
    </div>
  );
}
