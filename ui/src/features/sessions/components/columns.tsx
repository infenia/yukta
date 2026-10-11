// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { LegacyColumnDef } from '@tanstack/react-table/legacy';
import { Badge } from '@/components/ui/badge';
import type { SessionListItem } from '../types/session';
import { SessionRowActionsMenu } from './SessionRowActionsMenu';

export const columns: LegacyColumnDef<SessionListItem>[] = [
  {
    accessorKey: 'name',
    header: 'Name',
    cell: ({ row }: any) => (
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
              className="text-label-sm bg-secondary-container text-on-surface px-2"
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
    cell: ({ row }: any) => (
      <div className="text-body-sm font-mono">
        {row.getValue('projectPath')}
      </div>
    ),
  },
  {
    accessorKey: 'workflowCount',
    header: 'Workflows',
    cell: ({ row }: any) => (
      <div className="text-center">{row.getValue('workflowCount')}</div>
    ),
  },
  {
    id: 'actions',
    cell: ({ row }: any) => (
      <div onClick={(e) => e.stopPropagation()}>
        <SessionRowActionsMenu session={row.original} />
      </div>
    ),
  },
];
