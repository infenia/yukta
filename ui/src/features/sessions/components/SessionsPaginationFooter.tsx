// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { Table } from '@tanstack/react-table';
import { Button } from '@/components/ui/button';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import type { SessionListItem } from '../types/session';

interface SessionsPaginationFooterProps {
  table: Table<SessionListItem>;
}

export function SessionsPaginationFooter({ table }: SessionsPaginationFooterProps) {
  const pageSize = table.getState().pagination.pageSize;
  const pageIndex = table.getState().pagination.pageIndex;
  const totalRows = table.getFilteredRowModel().rows.length;

  const start = totalRows === 0 ? 0 : pageIndex * pageSize + 1;
  const end = Math.min((pageIndex + 1) * pageSize, totalRows);

  return (
    <div className="flex h-10 items-center justify-end gap-6 bg-surface-container-high px-4 text-xs text-on-surface-variant">
      <div className="flex items-center gap-2">
        <span className="text-body-sm">Rows per page:</span>
        <DropdownMenu>
          <DropdownMenuTrigger
            render={(state) => (
              <Button variant="outline" size="sm" {...state}>
                {pageSize}
                <span className="material-symbols-outlined ml-1" aria-hidden="true">arrow_drop_down</span>
              </Button>
            )}
          />
          <DropdownMenuContent>
            {[10, 20, 50].map((size) => (
              <DropdownMenuItem
                key={size}
                onClick={() => table.setPageSize(size)}
              >
                {size}
              </DropdownMenuItem>
            ))}
          </DropdownMenuContent>
        </DropdownMenu>
      </div>

      <div className="border-l border-outline-variant pl-4 text-body-sm">
        {start}–{end} of {totalRows} items
      </div>

      <div className="flex gap-1 border-l border-outline-variant pl-4">
        <Button
          variant="ghost"
          size="sm"
          onClick={() => table.firstPage()}
          disabled={!table.getCanPreviousPage()}
          className="disabled:opacity-30"
          aria-label="First page"
        >
          <span className="material-symbols-outlined" aria-hidden="true">first_page</span>
        </Button>
        <Button
          variant="ghost"
          size="sm"
          onClick={() => table.previousPage()}
          disabled={!table.getCanPreviousPage()}
          className="disabled:opacity-30"
          aria-label="Previous page"
        >
          <span className="material-symbols-outlined" aria-hidden="true">chevron_left</span>
        </Button>
        <Button
          variant="ghost"
          size="sm"
          onClick={() => table.nextPage()}
          disabled={!table.getCanNextPage()}
          className="disabled:opacity-30"
          aria-label="Next page"
        >
          <span className="material-symbols-outlined" aria-hidden="true">chevron_right</span>
        </Button>
        <Button
          variant="ghost"
          size="sm"
          onClick={() => table.lastPage()}
          disabled={!table.getCanNextPage()}
          className="disabled:opacity-30"
          aria-label="Last page"
        >
          <span className="material-symbols-outlined" aria-hidden="true">last_page</span>
        </Button>
      </div>
    </div>
  );
}
