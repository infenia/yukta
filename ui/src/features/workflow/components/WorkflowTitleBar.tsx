// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { ReactNode } from 'react';
import { StatusPill } from './StatusPill';

/**
 * Title row: the workflow ID as the title with the run's status beside it, the actions, and
 * the description underneath.
 */
export function WorkflowTitleBar({
  workflowId,
  description,
  status,
  actions,
}: {
  workflowId: string;
  description?: string;
  status: string | null;
  actions?: ReactNode;
}) {
  return (
    <div className="border-outline-variant bg-card flex flex-col gap-2 border-b px-4 py-3 sm:px-6">
      <div className="flex flex-wrap items-center gap-3">
        <h1 className="text-headline-lg text-on-surface font-mono tracking-tight break-all">
          {workflowId}
        </h1>
        <StatusPill status={status} />
        <div className="ml-auto flex flex-wrap items-center gap-2">
          {actions}
        </div>
      </div>
      {description && (
        <p className="text-body-sm text-muted-foreground max-w-prose">
          {description}
        </p>
      )}
    </div>
  );
}
