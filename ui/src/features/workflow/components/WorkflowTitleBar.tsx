// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { StatusChip } from './StatusChip';

/** Title row: workflow display name, monospace workflow ID and the execution status chip. */
export function WorkflowTitleBar({
  displayName,
  workflowId,
  status,
}: {
  displayName: string;
  workflowId: string;
  status: string | null;
}) {
  return (
    <div className="flex items-center gap-4 border-b border-outline-variant bg-surface px-6 py-4">
      <h1 className="text-2xl font-bold text-on-surface">{displayName}</h1>
      <span className="font-mono text-xs text-on-surface-variant">ID: {workflowId}</span>
      <div className="ml-auto">
        <StatusChip status={status} />
      </div>
    </div>
  );
}
