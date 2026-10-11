// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { ReactNode } from 'react';
import { StatusChip } from './StatusChip';

/** Title row: workflow display name, monospace workflow ID, optional actions and the status chip. */
export function WorkflowTitleBar({
  displayName,
  workflowId,
  status,
  actions,
}: {
  displayName: string;
  workflowId: string;
  status: string | null;
  actions?: ReactNode;
}) {
  return (
    <div className="flex flex-wrap items-center gap-4 border-b border-outline-variant bg-surface px-6 py-4">
      <h1 className="text-2xl font-bold text-on-surface">{displayName}</h1>
      <span className="font-mono text-xs text-on-surface-variant">ID: {workflowId}</span>
      <div className="ml-auto flex items-center gap-4">
        {actions}
        <StatusChip status={status} />
      </div>
    </div>
  );
}
