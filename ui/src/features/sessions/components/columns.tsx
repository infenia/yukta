// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { LegacyColumnDef } from '@tanstack/react-table/legacy';
import { Badge } from '@/components/ui/badge';
import { StatusPill } from '@/features/workflow/components/StatusPill';
import {
  formatClockTime,
  formatRelative,
} from '@/features/workflow/lib/timeFormat';
import { slugify } from '@/features/builder/components/SessionFormPage.helpers';
import type { SessionListItem } from '../types/session';
import { Tip } from '@/components/ui/tooltip';
import {
  copyIcon,
  copyTone,
  useCopyCommand,
} from '@/features/workflow/hooks/useCopyCommand';
import { cn } from '@/lib/utils';

/** The working directory as a button that copies it and turns green for a moment. */
function CopyPathCell({ path }: { path: string }) {
  const { status, copy } = useCopyCommand();
  return (
    <Tip label="Copy working directory">
      <button
        type="button"
        aria-label={`Copy working directory ${path}`}
        onClick={(e) => {
          e.stopPropagation();
          copy(path);
        }}
        className={cn(
          'text-code-sm inline-flex max-w-full items-center gap-1 rounded-md px-1 py-0.5 text-left transition-colors',
          status ? copyTone(status) : 'text-on-surface hover:bg-muted'
        )}
      >
        <span className="truncate">{path}</span>
        <span
          aria-hidden="true"
          className={cn(
            'material-symbols-outlined text-sm',
            !status && 'text-muted-foreground'
          )}
        >
          {copyIcon(status)}
        </span>
      </button>
    </Tip>
  );
}

/** A session row, with its newest run when the list found one. */
export type SessionRow = SessionListItem;

export const columns: LegacyColumnDef<SessionRow>[] = [
  {
    accessorKey: 'name',
    header: 'Name',
    cell: ({ row }) => (
      <div className="min-w-56">
        <div className="text-label-md text-primary">{row.getValue('name')}</div>
        {/* IDs are slugs of the name, so an ID that matches it adds nothing */}
        {row.original.sessionId !== slugify(row.original.name) && (
          <div className="text-code-sm text-on-surface-variant">
            {row.original.sessionId}
          </div>
        )}
        {row.original.description && (
          <div className="text-body-sm text-on-surface-variant mt-0.5 max-w-md truncate">
            {row.original.description}
          </div>
        )}
      </div>
    ),
  },
  {
    id: 'lastRun',
    header: 'Last run',
    cell: ({ row }) => {
      const run = row.original.lastRun;
      return run ? (
        <div className="flex items-center gap-2 whitespace-nowrap">
          <StatusPill status={run.status} iconOnly />
          <span className="text-body-sm text-muted-foreground">
            {run.workflowId} ·{' '}
            <time
              dateTime={run.startTime ?? undefined}
              title={formatClockTime(run.startTime)}
            >
              {formatRelative(run.startTime)}
            </time>
          </span>
        </div>
      ) : (
        <StatusPill status={null} iconOnly />
      );
    },
  },
  {
    accessorKey: 'workflowCount',
    header: 'Workflows',
    cell: ({ row }) => (
      <div className="tabular-nums">{row.getValue('workflowCount')}</div>
    ),
  },
  {
    accessorKey: 'initiator',
    header: 'Creator',
  },
  {
    accessorKey: 'tags',
    header: 'Tags',
    cell: ({ row }) => {
      const tags = row.getValue('tags') as string[];
      return (
        <div className="flex flex-row flex-wrap gap-1.5">
          {tags.map((tag, index) => (
            <Badge
              key={`${tag}-${index}`}
              className="bg-secondary-container text-label-sm text-on-surface px-2"
            >
              {tag}
            </Badge>
          ))}
        </div>
      );
    },
  },
  {
    accessorKey: 'projectPath',
    header: 'Working directory',
    cell: ({ row }) => <CopyPathCell path={row.original.projectPath} />,
  },
];
