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

/** The run picker: which run the page shows ("Run <id> · started <time>"). */
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
      <span
        className="text-body-sm text-on-surface-variant"
        data-testid="run-history-empty"
      >
        No runs yet
      </span>
    );
  }

  const selected = executions.find(
    (execution) => execution.executionId === selectedExecutionId
  );
  return (
    <DropdownMenu>
      <DropdownMenuTrigger
        className="flex h-8 items-center gap-2 rounded-full border border-border bg-card px-3 text-label-md text-on-surface hover:bg-muted"
        aria-label="Run history"
      >
        <span className="material-symbols-outlined text-base">history</span>
        Run
        <span className="font-mono">
          {selectedExecutionId?.slice(0, 8) ?? '—'}
        </span>
        {selected && (
          <span className="font-normal text-on-surface-variant">
            · started {formatClockTime(selected.startTime)}
          </span>
        )}
        <span className="material-symbols-outlined text-base">expand_more</span>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end">
        {executions.map((execution) => (
          <DropdownMenuItem
            key={execution.executionId}
            onClick={() => onSelect(execution.executionId)}
          >
            <span className="flex w-full items-center gap-3">
              <span className="material-symbols-outlined text-sm">
                {execution.executionId === selectedExecutionId
                  ? 'check'
                  : 'schedule'}
              </span>
              <span className="text-code-sm">{execution.executionId}</span>
              <span className="ml-auto text-body-sm text-on-surface-variant">
                {formatClockTime(execution.startTime)} · {execution.status}
              </span>
            </span>
          </DropdownMenuItem>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
