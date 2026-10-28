// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { Link } from 'react-router';
import { cardVariants } from '@/components/ui/card';
import { cn } from '@/lib/utils';
import type {
  WorkflowSummary,
  WorkflowExecutionStatus,
} from '../types/session';

interface WorkflowCardProps {
  sessionId: string;
  workflow: WorkflowSummary;
}

const STATUS_BADGE_STYLES: Record<WorkflowExecutionStatus | 'NOT_RUN', string> =
  {
    SUCCESS: 'text-success',
    FAILURE: 'text-error',
    ERROR: 'text-error',
    STOPPED: 'text-on-surface-variant',
    RUNNING: 'text-primary',
    PENDING: 'text-on-surface-variant',
    NOT_RUN: 'text-on-surface-variant',
  };

const STATUS_LABELS: Record<WorkflowExecutionStatus | 'NOT_RUN', string> = {
  SUCCESS: 'Completed',
  FAILURE: 'Failed',
  ERROR: 'Failed',
  STOPPED: 'Stopped',
  RUNNING: 'Running',
  PENDING: 'Pending',
  NOT_RUN: 'Not Run',
};

export function WorkflowCard({ sessionId, workflow }: WorkflowCardProps) {
  const statusKey: WorkflowExecutionStatus | 'NOT_RUN' =
    workflow.status || 'NOT_RUN';
  const badgeClass = STATUS_BADGE_STYLES[statusKey];
  const badgeLabel = STATUS_LABELS[statusKey];

  return (
    <Link
      to={`/sessions/${sessionId}/workflow/${workflow.workflowId}`}
      className={cn(
        cardVariants({ interactive: true }),
        'group block w-full p-5 text-left no-underline'
      )}
    >
      <div className="mb-4 flex items-center justify-between gap-3">
        {/* Icon tile, as on the docs cards */}
        <span
          className="flex size-9 shrink-0 items-center justify-center rounded-lg bg-accent text-accent-foreground"
          aria-hidden="true"
        >
          <span className="material-symbols-outlined">account_tree</span>
        </span>
        <span
          className={`ml-auto inline-flex items-center gap-2 text-label-sm ${badgeClass}`}
        >
          <span aria-hidden className="size-2 rounded-full bg-current" />
          {badgeLabel}
        </span>
        <span
          className="material-symbols-outlined text-on-surface-variant transition-standard group-hover:translate-x-1 group-hover:text-primary"
          aria-hidden="true"
        >
          arrow_forward
        </span>
      </div>

      <h3 className="mb-2 text-label-md break-all transition-standard group-hover:text-primary">
        {workflow.workflowId}
      </h3>

      <p className="mb-5 line-clamp-2 text-body-sm text-on-surface-variant">
        {workflow.description}
      </p>

      <div className="flex gap-4 text-body-sm text-on-surface">
        <div className="flex items-center gap-1.5">
          <span
            className="material-symbols-outlined text-sm text-on-surface-variant"
            aria-hidden="true"
          >
            account_tree
          </span>
          <span>{workflow.nodeCount} Nodes</span>
        </div>
        <div className="flex items-center gap-1.5">
          <span
            className="material-symbols-outlined text-sm text-on-surface-variant"
            aria-hidden="true"
          >
            rebase_edit
          </span>
          <span>{workflow.edgeCount} Edges</span>
        </div>
      </div>
    </Link>
  );
}
