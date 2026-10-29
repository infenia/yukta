// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { DeleteWorkflowDialog } from './DeleteDialogs';
import { useState } from 'react';
import { RequirePermission } from '@/components/common/RequirePermission';
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
  const [deleting, setDeleting] = useState(false);
  const statusKey: WorkflowExecutionStatus | 'NOT_RUN' =
    workflow.status || 'NOT_RUN';
  const badgeClass = STATUS_BADGE_STYLES[statusKey];
  const badgeLabel = STATUS_LABELS[statusKey];

  return (
    <div className="relative">
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
            className="bg-accent text-accent-foreground flex size-9 shrink-0 items-center justify-center rounded-lg"
            aria-hidden="true"
          >
            <span className="material-symbols-outlined">account_tree</span>
          </span>
          <span
            className={`text-label-sm ml-auto inline-flex items-center gap-2 ${badgeClass}`}
          >
            <span aria-hidden className="size-2 rounded-full bg-current" />
            {badgeLabel}
          </span>
          <span
            className="material-symbols-outlined text-on-surface-variant transition-standard group-hover:text-primary group-hover:translate-x-1"
            aria-hidden="true"
          >
            arrow_forward
          </span>
        </div>

        <h3 className="text-label-md transition-standard group-hover:text-primary mb-2 break-all">
          {workflow.workflowId}
        </h3>

        <p className="text-body-sm text-on-surface-variant mb-5 line-clamp-2">
          {workflow.description}
        </p>

        <div className="text-body-sm text-on-surface flex gap-4">
          <div className="flex items-center gap-1.5">
            <span
              className="material-symbols-outlined text-on-surface-variant text-sm"
              aria-hidden="true"
            >
              account_tree
            </span>
            <span>{workflow.nodeCount} Nodes</span>
          </div>
          <div className="flex items-center gap-1.5">
            <span
              className="material-symbols-outlined text-on-surface-variant text-sm"
              aria-hidden="true"
            >
              rebase_edit
            </span>
            <span>{workflow.edgeCount} Edges</span>
          </div>
        </div>
      </Link>
      <RequirePermission perm="deleteSessions">
        <button
          type="button"
          aria-label={`Delete workflow ${workflow.workflowId}`}
          onClick={() => setDeleting(true)}
          className="text-on-surface-variant hover:text-error absolute right-3 bottom-3 flex size-7 items-center justify-center rounded-lg"
        >
          <span className="material-symbols-outlined text-base">delete</span>
        </button>
      </RequirePermission>
      {deleting && (
        <DeleteWorkflowDialog
          sessionId={sessionId}
          workflowId={workflow.workflowId}
          onClose={() => setDeleting(false)}
        />
      )}
    </div>
  );
}
