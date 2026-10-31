// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { RequirePermission } from '@/components/common/RequirePermission';
import { useMemo, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router';
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
import { columns, type SessionRow } from './columns';
import { SessionsHeader } from './SessionsHeader';
import { SessionsFilterBar } from './SessionsFilterBar';
import {
  filterSessions,
  type SessionFilters,
} from './SessionsFilterBar.helpers';
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
  // The Tag and Initiator filters live in the URL (?tag=env=prod&initiator=alice) so a link shares the view.
  const [searchParams, setSearchParams] = useSearchParams();
  const tags = searchParams.getAll('tag');
  const initiators = searchParams.getAll('initiator');
  const lastRuns = searchParams.getAll('lastrun');
  const filters = useMemo<SessionFilters>(
    () => ({ tags, initiators, lastRuns }),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [searchParams]
  );
  const filtered = useMemo<SessionRow[]>(
    () => filterSessions(sessions, filters),
    [sessions, filters]
  );
  const setFilters = (next: SessionFilters) =>
    setSearchParams(
      (current) => {
        const params = new URLSearchParams(current);
        params.delete('tag');
        params.delete('initiator');
        params.delete('lastrun');
        next.tags.forEach((tag) => params.append('tag', tag));
        next.initiators.forEach((who) => params.append('initiator', who));
        next.lastRuns.forEach((kind) => params.append('lastrun', kind));
        return params;
      },
      { replace: true }
    );
  const [columnFilters, setColumnFilters] = useState<ColumnFiltersState>([]);

  // React Compiler skips memoizing useLegacyTable (headless API returns unstable functions); accepted

  const table = useLegacyTable({
    data: filtered,
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
      const { name, sessionId, description } = row.original;
      const needle = filterValue.toLowerCase();
      return [name, sessionId, description ?? ''].some((text) =>
        text.toLowerCase().includes(needle)
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
      <div className="px-4 py-6 sm:px-6">
        <StateMessage tone="loading" title="Loading sessions..." />
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
    setFilters({ tags: [], initiators: [], lastRuns: [] });
    table.setPageIndex(0);
  };

  const isEmpty = sessions.length === 0;
  const newSession = (
    <RequirePermission perm="editSessions">
      <Link
        to="/sessions/new"
        className={buttonVariants({
          variant: 'default',
          size: 'default',
        })}
      >
        <span className="material-symbols-outlined text-base">add</span>
        New session
      </Link>
    </RequirePermission>
  );

  return (
    <div className="bg-background flex flex-1 flex-col gap-0">
      <div className="relative isolate">
        {/* The docs hero backdrop, behind the header and filter bar only */}
        <div
          aria-hidden="true"
          data-testid="hero-backdrop"
          className={`hero-backdrop pointer-events-none absolute inset-x-0 top-0 -z-10 ${isEmpty ? 'h-128' : 'h-56'}`}
        />
        <SessionsHeader
          actions={
            isEmpty ? undefined : (
              <div className="flex gap-2">
                <Link
                  to="/sessions/import"
                  className={buttonVariants({
                    variant: 'outline',
                    size: 'default',
                  })}
                >
                  <span className="material-symbols-outlined text-base">
                    upload
                  </span>
                  Import JSON
                </Link>
                {newSession}
              </div>
            )
          }
        />

        {isEmpty ? (
          <section
            data-testid="sessions-empty-hero"
            className="flex flex-col items-center gap-4 px-4 pt-10 pb-20 text-center sm:px-6"
          >
            <span
              aria-hidden="true"
              className="bg-accent text-accent-foreground shadow-card flex size-12 items-center justify-center rounded-xl"
            >
              <span className="material-symbols-outlined">folder</span>
            </span>
            <h2 className="text-headline-lg text-on-surface sm:text-display-01 text-balance">
              No sessions yet
            </h2>
            <p className="text-body-md text-on-surface-variant max-w-prose">
              A session groups the workflows of one project. Create one, then
              add workflows from the editor, the CLI or an AI agent over MCP.
            </p>
            {newSession}
          </section>
        ) : (
          <>
            <SessionsFilterBar
              data={sessions}
              filters={filters}
              onFiltersChange={setFilters}
              globalFilter={globalFilter}
              onGlobalFilterChange={setGlobalFilter}
              onReset={handleReset}
            />

            <div className="px-4 pb-8 sm:px-6">
              <TableFrame>
                {/* Phones: each row stacks into a card of labelled values instead of a clipped table */}
                <Table className="max-sm:block [&_tbody]:max-sm:block">
                  <TableHeader className="max-sm:hidden">
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
                        <TableCell colSpan={columns.length} className="p-4">
                          <StateMessage
                            title="No sessions yet"
                            icon="folder"
                            compact
                            className="border-0 bg-transparent"
                          />
                        </TableCell>
                      </TableRow>
                    ) : (
                      table.getRowModel().rows.map((row: any) => (
                        <TableRow
                          key={row.id}
                          tabIndex={0}
                          onClick={() =>
                            navigate(
                              `/sessions/${encodeURIComponent(row.original.sessionId)}`
                            )
                          }
                          onKeyDown={(e) => {
                            if (e.key === 'Enter' || e.key === ' ') {
                              e.preventDefault();
                              navigate(
                                `/sessions/${encodeURIComponent(row.original.sessionId)}`
                              );
                            }
                          }}
                          className="cursor-pointer focus-visible:-outline-offset-2 max-sm:relative max-sm:flex max-sm:flex-col max-sm:gap-1 max-sm:py-3"
                        >
                          {row.getVisibleCells().map((cell: any) => (
                            <TableCell
                              key={cell.id}
                              data-label={
                                typeof cell.column.columnDef.header === 'string'
                                  ? cell.column.columnDef.header
                                  : undefined
                              }
                              className="max-sm:before:text-body-sm max-sm:before:text-muted-foreground px-4 max-sm:flex max-sm:items-center max-sm:gap-3 max-sm:py-0 max-sm:whitespace-normal max-sm:before:w-24 max-sm:before:shrink-0 max-sm:before:content-[attr(data-label)] max-sm:first:before:hidden max-sm:last:absolute max-sm:last:top-2 max-sm:last:right-1 max-sm:last:before:hidden"
                            >
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
          </>
        )}
      </div>
    </div>
  );
}
