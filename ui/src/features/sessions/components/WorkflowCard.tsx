// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { Link } from 'react-router';
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
      className="group border-outline-variant bg-surface transition-standard hover:bg-surface-variant focus-visible:outline-primary block w-full cursor-pointer border p-6 text-left focus-visible:outline-2 focus-visible:outline-offset-2"
    >
      <div className="mb-4 flex items-start justify-between">
        <span
          className={`text-label-sm inline-flex items-center gap-2 ${badgeClass}`}
        >
          <span aria-hidden className="size-2 rounded-full bg-current" />
          {badgeLabel}
        </span>
        <span
          className="material-symbols-outlined text-on-surface-variant transition-standard group-hover:translate-x-1"
          aria-hidden="true"
        >
          arrow_forward
        </span>
      </div>

      <h3 className="text-label-md transition-standard group-hover:text-primary mb-2 break-all">
        {workflow.workflowId}
      </h3>

      <p className="text-body-sm text-on-surface-variant mb-6 line-clamp-2">
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
  );
}
