// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { Link } from 'react-router';
import type { WorkflowSummary, WorkflowExecutionStatus } from '../types/session';

interface WorkflowCardProps {
  sessionId: string;
  workflow: WorkflowSummary;
}

const STATUS_BADGE_STYLES: Record<WorkflowExecutionStatus | 'NOT_RUN', string> = {
  SUCCESS: 'bg-tertiary-container text-on-tertiary-container',
  FAILURE: 'bg-error-container text-on-error-container',
  ERROR: 'bg-error-container text-on-error-container',
  RUNNING: 'bg-primary-container text-on-primary-container',
  PENDING: 'bg-secondary-container text-on-secondary-container',
  NOT_RUN: 'bg-secondary-container text-on-secondary-container',
};

const STATUS_LABELS: Record<WorkflowExecutionStatus | 'NOT_RUN', string> = {
  SUCCESS: 'Completed',
  FAILURE: 'Failed',
  ERROR: 'Failed',
  RUNNING: 'Running',
  PENDING: 'Pending',
  NOT_RUN: 'Not Run',
};

export function WorkflowCard({ sessionId, workflow }: WorkflowCardProps) {
  const statusKey: WorkflowExecutionStatus | 'NOT_RUN' = workflow.status || 'NOT_RUN';
  const badgeClass = STATUS_BADGE_STYLES[statusKey];
  const badgeLabel = STATUS_LABELS[statusKey];

  return (
    <Link
      to={`/sessions/${sessionId}/workflow/${workflow.workflowId}`}
      className="group block w-full cursor-pointer border border-outline-variant bg-surface p-6 text-left transition-standard hover:bg-surface-variant focus:outline-2 focus:outline-primary focus:outline-offset-2"
    >
      <div className="mb-4 flex items-start justify-between">
        <span className={`px-2 py-0.5 text-[10px] font-bold uppercase ${badgeClass}`}>
          {badgeLabel}
        </span>
        <span className="material-symbols-outlined text-on-surface-variant transition-standard group-hover:translate-x-1" aria-hidden="true">
          arrow_forward
        </span>
      </div>

      <h3 className="mb-2 text-base font-semibold transition-standard group-hover:text-primary">
        {workflow.workflowId}
      </h3>

      <p className="mb-6 line-clamp-2 text-xs leading-relaxed text-on-surface-variant">
        {workflow.description}
      </p>

      <div className="flex gap-4 text-xs font-medium text-on-surface">
        <div className="flex items-center gap-1.5">
          <span className="material-symbols-outlined text-sm text-on-surface-variant" aria-hidden="true">
            account_tree
          </span>
          <span>{workflow.nodeCount} Nodes</span>
        </div>
        <div className="flex items-center gap-1.5">
          <span className="material-symbols-outlined text-sm text-on-surface-variant" aria-hidden="true">
            rebase_edit
          </span>
          <span>{workflow.edgeCount} Edges</span>
        </div>
      </div>
    </Link>
  );
}
