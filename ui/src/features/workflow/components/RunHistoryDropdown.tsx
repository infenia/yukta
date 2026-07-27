// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { formatClockTime } from '../lib/timeFormat';
import type { ExecutionSummary } from '../types/workflow';

/** Breadcrumb-bar dropdown selecting which execution (run) the page shows. */
export function RunHistoryDropdown({
  executions,
  selectedExecutionId,
  onSelect,
}: {
  executions: ExecutionSummary[];
  selectedExecutionId: string | null;
  onSelect: (executionId: string) => void;
}) {
  if (executions.length === 0) {
    return (
      <span className="text-xs text-on-surface-variant" data-testid="run-history-empty">
        No runs yet
      </span>
    );
  }

  return (
    <DropdownMenu>
      <DropdownMenuTrigger
        className="flex items-center gap-1 text-xs text-on-surface transition-colors hover:text-primary"
        aria-label="Run history"
      >
        <span className="material-symbols-outlined text-sm">history</span>
        <span className="font-medium">Run History:</span>
        <span className="font-mono">{selectedExecutionId ?? '—'}</span>
        <span className="material-symbols-outlined text-sm">arrow_drop_down</span>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end">
        {executions.map((execution) => (
          <DropdownMenuItem
            key={execution.executionId}
            onClick={() => onSelect(execution.executionId)}
          >
            <span className="flex w-full items-center gap-3">
              <span className="material-symbols-outlined text-sm">
                {execution.executionId === selectedExecutionId ? 'check' : 'schedule'}
              </span>
              <span className="font-mono text-xs">{execution.executionId}</span>
              <span className="ml-auto text-xs text-on-surface-variant">
                {formatClockTime(execution.startTime)} · {execution.status}
              </span>
            </span>
          </DropdownMenuItem>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
