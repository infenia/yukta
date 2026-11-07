// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import BreadcrumbNav from '@/components/layout/BreadcrumbNav';
import type { ReactNode } from 'react';

/**
 * Title row: the workflow ID with the run picker beside it (left), the run controls and
 * actions right-aligned, and the description underneath.
 */
export function WorkflowTitleBar({
  workflowId,
  description,
  runGroup,
  controls,
  actions,
}: {
  workflowId: string;
  description?: string;
  /** Run selector, left-aligned right after the title. */
  runGroup?: ReactNode;
  /** Run, pause, stop and restart, right-aligned before the actions. */
  controls?: ReactNode;
  actions?: ReactNode;
}) {
  return (
    <div className="border-outline-variant relative isolate flex flex-col gap-2 border-b px-4 py-3 sm:px-6">
      {/* The docs hero backdrop, as on the Sessions list: behind the title row only */}
      <div
        aria-hidden="true"
        data-testid="hero-backdrop"
        className="hero-backdrop pointer-events-none absolute inset-0 -z-10"
      />
      <BreadcrumbNav />
      <div className="flex flex-wrap items-center gap-3">
        <h1 className="text-headline-lg text-on-surface font-mono tracking-tight break-all">
          {workflowId}
        </h1>
        {runGroup}
        <div className="ml-auto flex flex-wrap items-center justify-end gap-2">
          {controls}
          {actions}
        </div>
      </div>
      {description && (
        <p className="text-body-sm text-muted-foreground">{description}</p>
      )}
    </div>
  );
}
