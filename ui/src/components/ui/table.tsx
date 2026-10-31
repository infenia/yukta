// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import * as React from 'react';
import { cn } from '@/lib/utils';

/*
 * The app's one table style, after the docs theme's tables: a raised, rounded frame
 * (TableFrame), a muted header row in small semibold type, hairline rows with a soft hover.
 * Wrap a Table (and anything that belongs inside the frame, such as pagination) in TableFrame.
 */
function TableFrame({ className, ...props }: React.ComponentProps<'div'>) {
  return (
    <div
      data-slot="table-frame"
      className={cn(
        'overflow-hidden rounded-xl border border-border bg-card shadow-card',
        className
      )}
      {...props}
    />
  );
}

function Table({ className, ...props }: React.ComponentProps<'table'>) {
  return (
    <div
      data-slot="table-container"
      className="relative w-full overflow-x-auto"
    >
      <table
        data-slot="table"
        className={cn('w-full caption-bottom text-body-md', className)}
        {...props}
      />
    </div>
  );
}

function TableHeader({ className, ...props }: React.ComponentProps<'thead'>) {
  return (
    <thead
      data-slot="table-header"
      className={cn(
        'bg-muted [&_tr]:border-b [&_tr]:border-border [&_tr]:hover:bg-transparent',
        className
      )}
      {...props}
    />
  );
}

function TableBody({ className, ...props }: React.ComponentProps<'tbody'>) {
  return (
    <tbody
      data-slot="table-body"
      className={cn('[&_tr:last-child]:border-0', className)}
      {...props}
    />
  );
}

function TableFooter({ className, ...props }: React.ComponentProps<'tfoot'>) {
  return (
    <tfoot
      data-slot="table-footer"
      className={cn(
        'border-t bg-muted/50 font-medium [&>tr]:last:border-b-0',
        className
      )}
      {...props}
    />
  );
}

function TableRow({ className, ...props }: React.ComponentProps<'tr'>) {
  return (
    <tr
      data-slot="table-row"
      className={cn(
        'border-b border-border transition-standard hover:bg-muted/60 has-aria-expanded:bg-muted/60 data-[state=selected]:bg-accent',
        className
      )}
      {...props}
    />
  );
}

function TableHead({ className, ...props }: React.ComponentProps<'th'>) {
  return (
    <th
      data-slot="table-head"
      scope="col"
      className={cn(
        'h-10 px-3 text-left align-middle text-overline whitespace-nowrap has-[[role=checkbox]]:pr-0',
        className
      )}
      {...props}
    />
  );
}

function TableCell({ className, ...props }: React.ComponentProps<'td'>) {
  return (
    <td
      data-slot="table-cell"
      className={cn(
        'px-3 py-2.5 align-middle whitespace-nowrap text-on-surface has-[[role=checkbox]]:pr-0',
        className
      )}
      {...props}
    />
  );
}

function TableCaption({
  className,
  ...props
}: React.ComponentProps<'caption'>) {
  return (
    <caption
      data-slot="table-caption"
      className={cn('mt-4 text-body-sm text-muted-foreground', className)}
      {...props}
    />
  );
}

export {
  TableFrame,
  Table,
  TableHeader,
  TableBody,
  TableFooter,
  TableHead,
  TableRow,
  TableCell,
  TableCaption,
};
