// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { LegacyColumnDef } from '@tanstack/react-table/legacy';
import { Badge } from '@/components/ui/badge';
import { StatusPill } from '@/features/workflow/components/StatusPill';
import { formatClockTime } from '@/features/workflow/lib/timeFormat';
import type { SessionListItem } from '../types/session';
import { SessionRowActionsMenu } from './SessionRowActionsMenu';

/** A session row, with its newest run when the list found one. */
export type SessionRow = SessionListItem;

export const columns: LegacyColumnDef<SessionRow>[] = [
  {
    accessorKey: 'name',
    header: 'Name',
    cell: ({ row }) => (
      <div className="min-w-56">
        <div className="text-label-md text-primary">{row.getValue('name')}</div>
        <div className="text-code-sm text-on-surface-variant">
          {row.original.sessionId}
        </div>
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
          <StatusPill status={run.status} />
          <span className="text-body-sm text-muted-foreground">
            {run.workflowId} · {formatClockTime(run.startTime)}
          </span>
        </div>
      ) : (
        <span className="text-muted-foreground">Not run</span>
      );
    },
  },
  {
    accessorKey: 'workflowCount',
    header: 'Workflows',
    cell: ({ row }) => (
      <div className="tabular-nums">
        {row.getValue('workflowCount')}
      </div>
    ),
  },
  {
    accessorKey: 'initiator',
    header: 'Initiator',
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
    header: 'Project path',
    cell: ({ row }) => (
      <button
        type="button"
        title="Copy project path"
        aria-label={`Copy project path ${row.original.projectPath}`}
        onClick={(e) => {
          e.stopPropagation();
          void navigator.clipboard?.writeText(row.original.projectPath);
        }}
        className="text-code-sm text-on-surface hover:bg-muted inline-flex max-w-full items-center gap-1 rounded-md px-1 py-0.5 text-left"
      >
        <span className="truncate">{row.getValue('projectPath')}</span>
        <span
          aria-hidden="true"
          className="material-symbols-outlined text-muted-foreground text-sm"
        >
          content_copy
        </span>
      </button>
    ),
  },
  {
    id: 'actions',
    cell: ({ row }) => (
      <div onClick={(e) => e.stopPropagation()}>
        <SessionRowActionsMenu session={row.original} />
      </div>
    ),
  },
];
