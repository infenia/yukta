// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { ColumnDef } from '@tanstack/react-table';
import { Badge } from '@/components/ui/badge';
import type { SessionListItem } from '../types/session';
import { SessionRowActionsMenu } from './SessionRowActionsMenu';

export const columns: ColumnDef<SessionListItem>[] = [
  {
    accessorKey: 'name',
    header: 'Name',
    cell: ({ row }) => (
      <div className="text-primary dark:text-primary">{row.getValue('name')}</div>
    ),
  },
  {
    accessorKey: 'sessionId',
    header: 'Session ID',
    cell: ({ row }) => (
      <div className="text-body-sm font-mono">{row.getValue('sessionId')}</div>
    ),
  },
  {
    accessorKey: 'description',
    header: 'Description',
    cell: ({ row }) => (
      <div className="max-w-xs truncate">{row.getValue('description')}</div>
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
        <div className="flex flex-row gap-1.5">
          {tags.map((tag, index) => (
            <Badge
              key={`${tag}-${index}`}
              className="bg-secondary-container text-[10px] font-bold tracking-tighter text-on-surface uppercase"
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
    header: 'Project Path',
    cell: ({ row }) => (
      <div className="text-body-sm font-mono">{row.getValue('projectPath')}</div>
    ),
  },
  {
    accessorKey: 'workflowCount',
    header: 'Workflows',
    cell: ({ row }) => (
      <div className="text-center">{row.getValue('workflowCount')}</div>
    ),
  },
  {
    id: 'actions',
    cell: ({ row }) => <SessionRowActionsMenu session={row.original} />,
  },
];
